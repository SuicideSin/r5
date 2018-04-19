package com.conveyal.r5.profile;

import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * RaptorWorker is fast, but FastRaptorWorker is knock-your-socks-off fast, and also more maintainable.
 * It is also simpler, as it only focuses on the transit network; see the Propagater class for the methods that extend
 * the travel times from the final transit stop of a trip out to the individual targets.
 *
 * The algorithm used herein is described in
 *
 * Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. “Evidence-Based Transit and Land Use Sketch Planning
 *   Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks.” Transportation Research
 *   Record 2653 (2017). doi:10.3141/2653-06.
 *
 * Delling, Daniel, Thomas Pajor, and Renato Werneck. “Round-Based Public Transit Routing,” January 1, 2012.
 *   http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
 *
 * There is currently no support for saving paths.
 *
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing computation by a
 * generic function-execution service like AWS Lambda. The gains in efficiency were significant enough that this is now
 * the way we do all analysis work. This system also accounts for pure-frequency routes by using Monte Carlo methods
 * (generating randomized schedules).
 */
public class FastRaptorWorker {

    /**
     * This value essentially serves as Infinity for ints - it's bigger than every other number.
     * It is the travel time to a transit stop or a target before that stop or target is ever reached.
     * Be careful when propagating travel times from stops to targets, adding something to UNREACHED will cause overflow.
     */
    public static final int UNREACHED = Integer.MAX_VALUE;

    /** Minimum slack time to board transit in seconds. */
    public static final int BOARD_SLACK_SECONDS = 60;

    private static final Logger LOG = LoggerFactory.getLogger(FastRaptorWorker.class);

    /**
     * Step for departure times. Use caution when changing this as we use the functions
     * request.getTimeWindowLengthMinutes and request.getMonteCarloDrawsPerMinute below which assume this value is 1 minute.
     * The same functions are also used in BootstrappingTravelTimeReducer where we assume that their product is the number
     * of iterations performed.
     */
    private static final int DEPARTURE_STEP_SEC = 60;

    /** Minimum wait for boarding to account for schedule variation */
    private static final int MINIMUM_BOARD_WAIT_SEC = 60;
    public final int nMinutes;
    public final int monteCarloDrawsPerMinute;

    // Variables to track time spent, all in nanoseconds (some of the operations we're timing are significantly submillisecond)
    // (although I suppose using ms would be fine because the number of times we cross a millisecond boundary would be proportional
    //  to the portion of a millisecond that operation took).
    public long startClockTime;
    public long timeInScheduledSearch;
    public long timeInScheduledSearchTransit;
    public long timeInScheduledSearchFrequencyBounds;
    public long timeInScheduledSearchTransfers;

    public long timeInFrequencySearch;
    public long timeInFrequencySearchFrequency;
    public long timeInFrequencySearchScheduled;
    public long timeInFrequencySearchTransfers;

    /** the transit layer to route on */
    private final TransitLayer transit;

    /** Times to access each transit stop using the street network (seconds) */
    private final TIntIntMap accessStops;

    /** The profilerequest describing routing parameters */
    private final ProfileRequest request;

    /** Frequency-based trip patterns running on a given day */
    private TripPattern[] runningFrequencyPatterns;

    /** Schedule-based trip patterns running on a given day */
    private TripPattern[] runningScheduledPatterns;

    /** Map from internal, filtered frequency pattern indices back to original pattern indices for frequency patterns */
    private int[] originalPatternIndexForFrequencyIndex;

    /** Map from internal, filtered pattern indices back to original pattern indices for scheduled patterns */
    private int[] originalPatternIndexForScheduledIndex;

    /** Array mapping from original pattern indices to the filtered frequency indices */
    private int[] frequencyIndexForOriginalPatternIndex;

    /** Array mapping from original pattern indices to the filtered scheduled indices */
    private int[] scheduledIndexForOriginalPatternIndex;

    private FrequencyRandomOffsets offsets;

    /** Services active on the date of the search */
    private final BitSet servicesActive;

    // TODO add javadoc to field
    private final RaptorState[] scheduleState;

    /** Set to true to save path details for all optimal paths. */
    public boolean retainPaths = false;

    /** If we're going to store paths to every destination (e.g. for static sites) then they'll be retained here. */
    public List<Path[]> pathsPerIteration;

    public FastRaptorWorker (TransitLayer transitLayer, ProfileRequest request, TIntIntMap accessStops) {
        this.transit = transitLayer;
        this.request = request;
        this.accessStops = accessStops;
        this.servicesActive  = transit.getActiveServicesForDate(request.date);
        // we add one to request.maxRides, first state is result of initial walk
        this.scheduleState = IntStream.range(0, request.maxRides + 1)
                .mapToObj((i) -> new RaptorState(transit.getStopCount(), request.maxTripDurationMinutes * 60))
                .toArray(RaptorState[]::new);

        for (int i = 1; i < this.scheduleState.length; i++) this.scheduleState[i].previous = this.scheduleState[i - 1];

        offsets = new FrequencyRandomOffsets(transitLayer);

        // compute number of minutes for scheduled search
        nMinutes = request.getTimeWindowLengthMinutes();

        // how many monte carlo draws per minute of scheduled search to get desired total iterations?
        monteCarloDrawsPerMinute = request.getMonteCarloDrawsPerMinute();
    }

    /**
     * For each iteration (minute + MC draw combination), return the minimum travel time to each transit stop in seconds.
     * Return value dimension order is [searchIteration][transitStopIndex]
     */
    public int[][] route () {

        startClockTime = System.nanoTime();
        prefilterPatterns();
        LOG.info("Performing {} scheduled iterations each with {} Monte Carlo draws for a total of {} iterations",
                nMinutes, monteCarloDrawsPerMinute, nMinutes * monteCarloDrawsPerMinute);

        // Initialize result storage.
        // Results are one arrival time at each stop, for every raptor iteration.
        int[][] arrivalTimesAtStopsPerIteration = new int[nMinutes * monteCarloDrawsPerMinute][];
        if (retainPaths) pathsPerIteration = new ArrayList<>();
        int currentIteration = 0;

        // The main outer loop iterates backward over all minutes in the departure times window.
        for (int departureTime = request.toTime - DEPARTURE_STEP_SEC, minute = nMinutes;
             departureTime >= request.fromTime;
             departureTime -= DEPARTURE_STEP_SEC, minute--) {

            if (minute % 15 == 0) LOG.debug("  minute {}", minute);

            // Run the raptor search. For this particular departure time, we receive N arrays of arrival times at all
            // stops, one for each randomized schedule: resultsForMinute[randScheduleNumber][transitStop]
            int[][] resultsForMinute = runRaptorForMinute(departureTime, monteCarloDrawsPerMinute);

            // Bypass Java's "effectively final" nonsense.
            // FIXME we could avoid this "final" weirdness by just using non-stream explicit loop syntax over the stops.
            // TODO clarify identifiers and explain how results are being unrolled from minutes into 'iterations'.
            final int finalDepartureTime = departureTime;
            for (int[] arrivalTimesAtStops : resultsForMinute) {
                // NB this copies the array, so we don't have issues with it being updated later
                arrivalTimesAtStopsPerIteration[currentIteration++] = IntStream.of(arrivalTimesAtStops)
                        .map(r -> r != UNREACHED ? r - finalDepartureTime : r)
                        .toArray();
            }
        }

        LOG.info("Search completed in {}s", (System.nanoTime() - startClockTime) / 1e9d);
        LOG.info("Scheduled/bounds search: {}s", timeInScheduledSearch / 1e9d);
        LOG.info("  - Scheduled search: {}s", timeInScheduledSearchTransit / 1e9d);
        LOG.info("  - Frequency upper bounds: {}s", timeInScheduledSearchFrequencyBounds / 1e9d);
        LOG.info("  - Transfers: {}s", timeInScheduledSearchTransfers / 1e9d);
        LOG.info("Frequency search: {}s", timeInFrequencySearch / 1e9d);
        LOG.info("  - Frequency component: {}s", timeInFrequencySearchFrequency / 1e9d);
        LOG.info("  - Resulting updates to scheduled component: {}s", timeInFrequencySearchScheduled / 1e9d);
        LOG.info("  - Transfers: {}s", timeInFrequencySearchTransfers / 1e9d);

        return arrivalTimesAtStopsPerIteration;
    }

    /** Prefilter the patterns to only ones that are running */
    private void prefilterPatterns () {
        TIntList frequencyPatterns = new TIntArrayList();
        TIntList scheduledPatterns = new TIntArrayList();
        frequencyIndexForOriginalPatternIndex = new int[transit.tripPatterns.size()];
        Arrays.fill(frequencyIndexForOriginalPatternIndex, -1);
        scheduledIndexForOriginalPatternIndex = new int[transit.tripPatterns.size()];
        Arrays.fill(scheduledIndexForOriginalPatternIndex, -1);

        int patternIndex = -1; // first increment lands at 0
        int frequencyIndex = 0;
        int scheduledIndex = 0;
        for (TripPattern pattern : transit.tripPatterns) {
            patternIndex++;
            RouteInfo routeInfo = transit.routes.get(pattern.routeIndex);
            TransitModes mode = TransitLayer.getTransitModes(routeInfo.route_type);
            if (pattern.servicesActive.intersects(servicesActive) && request.transitModes.contains(mode)) {
                // at least one trip on this pattern is relevant, based on the profile request's date and modes
                if (pattern.hasFrequencies) {
                    frequencyPatterns.add(patternIndex);
                    frequencyIndexForOriginalPatternIndex[patternIndex] = frequencyIndex++;
                }
                if (pattern.hasSchedules) { // NB not else b/c we still support combined frequency and schedule patterns.
                    scheduledPatterns.add(patternIndex);
                    scheduledIndexForOriginalPatternIndex[patternIndex] = scheduledIndex++;
                }
            }
        }

        originalPatternIndexForFrequencyIndex = frequencyPatterns.toArray();
        originalPatternIndexForScheduledIndex = scheduledPatterns.toArray();

        runningFrequencyPatterns = IntStream.of(originalPatternIndexForFrequencyIndex)
                .mapToObj(transit.tripPatterns::get).toArray(TripPattern[]::new);
        runningScheduledPatterns = IntStream.of(originalPatternIndexForScheduledIndex)
                .mapToObj(transit.tripPatterns::get).toArray(TripPattern[]::new);

        LOG.info("Prefiltering patterns based on date active reduced {} patterns to {} frequency and {} scheduled patterns",
                transit.tripPatterns.size(), frequencyPatterns.size(), scheduledPatterns.size());
    }

    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute
     */
    private void advanceScheduledSearchToPreviousMinute (int nextMinuteDepartureTime) {
        for (RaptorState state : this.scheduleState) {
            state.setDepartureTime(nextMinuteDepartureTime);

            // clear all touched stops to avoid constant reëxploration
            state.bestStopsTouched.clear();
            state.nonTransferStopsTouched.clear();
            // TODO prune trips that are now longer than max lengths to avoid biasing averages
        }


        // add initial stops
        RaptorState initialState = scheduleState[0];
        accessStops.forEachEntry((stop, accessTime) -> {
            initialState.setTimeAtStop(stop, accessTime + nextMinuteDepartureTime, -1, -1, 0, 0, true, -1, -1, -1);
            return true; // continue iteration
        });
    }

    /**
     * Perform one minute of a RAPTOR search.
     *
     * @param departureTime When this search departs.
     * @param iterationsPerMinute When frequencies are present, we perform multiple searches per departure minute using
     *                            different randomly-generated schedules (Monte Carlo search); this parameter controls
     *                            how many.
     * @return an array of length iterationsPerMinute, containing the arrival (clock) times at each stop for each iteration.
     */
    private int[][] runRaptorForMinute (int departureTime, int iterationsPerMinute) {
        advanceScheduledSearchToPreviousMinute(departureTime);

        // Run the scheduled search
        // round 0 is the street search
        // We are using the Range-RAPTOR extension described in Delling, Daniel, Thomas Pajor, and Renato Werneck.
        // “Round-Based Public Transit Routing,” January 1, 2012. http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
        // ergo, we re-use the arrival times found in searches that have already occurred that depart later, because
        // the arrival time given departure at time t is upper-bounded by the arrival time given departure at minute t + 1.
        if (transit.hasSchedules) {
            long startTime = System.nanoTime();
            for (int round = 1; round <= request.maxRides; round++) {
                // NB since we have transfer limiting not bothering to cut off search when there are no more transfers
                // as that will be rare and complicates the code grabbing the results

                // prevent finding crazy multi-transfer ways to get somewhere when there is a quicker way with fewer
                // transfers
                scheduleState[round].min(scheduleState[round - 1]);

                long scheduledStartTime = System.nanoTime();
                doScheduledSearchForRound(scheduleState[round - 1], scheduleState[round]);
                timeInScheduledSearchTransit += System.nanoTime() - scheduledStartTime;

                long transferStartTime = System.nanoTime();
                doTransfers(scheduleState[round]);
                timeInScheduledSearchTransfers += System.nanoTime() - transferStartTime;
            }
            timeInScheduledSearch += System.nanoTime() - startTime;
        }

        // If there are no frequency trips, return the result of the scheduled search, but repeated as many times
        // as there are requested MC draws, so that the scheduled search accessibility avoids potential bugs
        // where assumptions are made about how many results will be returned from a search, e.g., in
        // https://github.com/conveyal/r5/issues/306
        // FIXME on large networks with no frequency routes this seems extremely inefficient.
        // It may be somewhat less inefficient than it seems if we make arrays of references all to the same object.
        // TODO check whether we're actually hitting this code with iterationsPerMinute > 1 on scheduled networks.
        int[][] result = new int[iterationsPerMinute][];
        RaptorState finalRoundState = scheduleState[request.maxRides];
        // This scheduleState is repeatedly modified as the outer loop progresses over departure minutes.
        // We have to be careful here that creating these paths does not modify the state, and makes
        // protective copies of any information we want to retain.
        Path[] paths = retainPaths ? pathToEachStop(finalRoundState) : null;
        for (int iteration = 0; iteration < monteCarloDrawsPerMinute; iteration++) {
            result[iteration] = finalRoundState.bestNonTransferTimes;
            if (retainPaths) {
                pathsPerIteration.add(paths);
            }
        }
        return result;
    }

    /**
     * Create the optimal path to each stop in the transit network, based on the given RaptorState.
     */
    private static Path[] pathToEachStop (RaptorState state) {
        int nStops = state.bestNonTransferTimes.length;
        Path[] paths = new Path[nStops];
        for (int s = 0; s < nStops; s++) {
            if (state.bestNonTransferTimes[s] == UNREACHED) {
                paths[s] = null;
            } else {
                paths[s] = new Path(state, s);
            }
        }
        return paths;
    }

    /** Perform a scheduled search */
    private void doScheduledSearchForRound(RaptorState inputState, RaptorState outputState) {
        BitSet patternsTouched = getPatternsTouchedForStops(inputState, scheduledIndexForOriginalPatternIndex);

        for (int patternIndex = patternsTouched.nextSetBit(0); patternIndex >= 0; patternIndex = patternsTouched.nextSetBit(patternIndex + 1)) {
            int originalPatternIndex = originalPatternIndexForScheduledIndex[patternIndex];
            TripPattern pattern = runningScheduledPatterns[patternIndex];
            int onTrip = -1;
            int waitTime = 0;
            int boardTime = 0;
            int boardStop = -1;
            TripSchedule schedule = null;

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                int stop = pattern.stops[stopPositionInPattern];

                // attempt to alight if we're on board, done above the board search so that we don't check for alighting
                // when boarding
                if (onTrip > -1) {
                    int alightTime = schedule.arrivals[stopPositionInPattern];
                    int onVehicleTime = alightTime - boardTime;

                    if (waitTime + onVehicleTime + inputState.bestTimes[boardStop] > alightTime) {
                        LOG.error("Components of travel time are larger than travel time!");
                    }

                    outputState.setTimeAtStop(stop, alightTime, originalPatternIndex, boardStop, waitTime, onVehicleTime, false, pattern.tripSchedules.indexOf(schedule), boardTime, -1);
                }

                int sourcePatternIndex = inputState.previousStop[stop] == -1 ?
                        inputState.previousPatterns[stop] :
                        inputState.previousPatterns[inputState.previousStop[stop]];

                // Don't attempt to board if this stop was not reached in the last round, and don't attempt to
                // reboard the same pattern
                if (inputState.bestStopsTouched.get(stop) && sourcePatternIndex != originalPatternIndex) {
                    int earliestBoardTime = inputState.bestTimes[stop] + MINIMUM_BOARD_WAIT_SEC;

                    // only attempt to board if the stop was touched
                    if (onTrip == -1) {
                        if (inputState.bestStopsTouched.get(stop)) {
                            int candidateTripIndex = -1;
                            EARLIEST_TRIP:
                            for (TripSchedule candidateSchedule : pattern.tripSchedules) {
                                candidateTripIndex++;

                                if (!servicesActive.get(candidateSchedule.serviceCode) || candidateSchedule.headwaySeconds != null) {
                                    // frequency trip or not running
                                    continue;
                                }

                                if (earliestBoardTime < candidateSchedule.departures[stopPositionInPattern]) {
                                    // board this vehicle
                                    onTrip = candidateTripIndex;
                                    schedule = candidateSchedule;
                                    boardTime = candidateSchedule.departures[stopPositionInPattern];
                                    waitTime = boardTime - inputState.bestTimes[stop];
                                    boardStop = stop;
                                    break EARLIEST_TRIP;
                                }
                            }
                        }
                    } else {
                        // check if we can back up to an earlier trip due to this stop being reached earlier
                        int bestTripIdx = onTrip;
                        while (--bestTripIdx >= 0) {
                            TripSchedule trip = pattern.tripSchedules.get(bestTripIdx);
                            if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode)) {
                                // This is a frequency trip or it is not running on the day of the search.
                                continue;
                            }
                            if (trip.departures[stopPositionInPattern] > earliestBoardTime) {
                                onTrip = bestTripIdx;
                                schedule = trip;
                                boardTime = trip.departures[stopPositionInPattern];
                                waitTime = boardTime - inputState.bestTimes[stop];
                                boardStop = stop;
                            } else {
                                // this trip arrives too early, break loop since they are sorted by departure time
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void doTransfers (RaptorState state) {
        // avoid integer casts in tight loop below
        int walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * 1000);
        int maxWalkMillimeters = (int) (request.walkSpeed * request.maxWalkTime * 60 * 1000);

        for (int stop = state.nonTransferStopsTouched.nextSetBit(0); stop > -1; stop = state.nonTransferStopsTouched.nextSetBit(stop + 1)) {
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            TIntList transfersFromStop = transit.transfersForStop.get(stop);
            if (transfersFromStop != null) {
                for (int stopIdx = 0; stopIdx < transfersFromStop.size(); stopIdx += 2) {
                    int targetStop = transfersFromStop.get(stopIdx);
                    int distanceToTargetStopMillimeters = transfersFromStop.get(stopIdx + 1);

                    if (distanceToTargetStopMillimeters < maxWalkMillimeters) {
                        // transfer length to stop is acceptable
                        int walkTimeToTargetStopSeconds = distanceToTargetStopMillimeters / walkSpeedMillimetersPerSecond;
                        int timeAtTargetStop = state.bestNonTransferTimes[stop] + walkTimeToTargetStopSeconds;

                        if (walkTimeToTargetStopSeconds < 0) {
                            LOG.error("Negative transfer time!!");
                        }

                        state.setTimeAtStop(targetStop, timeAtTargetStop, -1, stop, 0, 0, true, -1, -1, walkTimeToTargetStopSeconds);
                    }
                }
            }
        }
    }

    /**
     * Get a list of the internal IDs of the patterns "touched" using the given index (frequency or scheduled)
     * "touched" means they were reached in the last round, and the index maps from the original pattern index to the
     * local index of the filtered patterns.
     */
    private BitSet getPatternsTouchedForStops(RaptorState state, int[] index) {
        BitSet patternsTouched = new BitSet();

        for (int stop = state.bestStopsTouched.nextSetBit(0); stop >= 0; stop = state.bestStopsTouched.nextSetBit(stop + 1)) {
            // copy stop to a new final variable to get around Java 8 "effectively final" nonsense
            final int finalStop = stop;
            transit.patternsForStop.get(stop).forEach(originalPattern -> {
                int filteredPattern = index[originalPattern];

                if (filteredPattern < 0) {
                    return true; // this pattern does not exist in the local subset of patterns, continue iteration
                }

                int sourcePatternIndex = state.previousStop[finalStop] == -1 ?
                        state.previousPatterns[finalStop] :
                        state.previousPatterns[state.previousStop[finalStop]];

                if (sourcePatternIndex != originalPattern) {
                    // don't re-explore the same pattern we used to reach this stop
                    // we forbid riding the same pattern twice in a row in the search code above, this will prevent
                    // us even having to loop over the stops in the pattern if potential board stops were only reached
                    // using this pattern.
                    patternsTouched.set(filteredPattern);
                }
                return true; // continue iteration
            });
        }

        return patternsTouched;
    }
}
