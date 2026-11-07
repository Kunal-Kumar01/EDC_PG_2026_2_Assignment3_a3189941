import nz.sodium.*;
import swidgets.*;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

/**
 * GUI that displays GPS tracker data processed via Sodium FRP.
 */
public class GpsGUI {

    private static final int NUM_TRACKERS = 10;
    private static final double FEET_TO_METERS = 0.3048;
    private static final double METERS_PER_DEGREE = 111_320.0;
    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;

    private GpsService service;
    private Stream<GpsEvent>[] streams;
    private JFrame frame;

    /**
     * Creates the GPS GUI, initialising the GPS service and building the display.
     */
    public GpsGUI() {
        service = new GpsService();
        streams = service.getEventStreams();

        frame = new JFrame("GPS Tracker Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        Tuple2<JPanel, Cell<double[]>> rangeControls = buildRangeControls();

        frame.add(buildTrackerPanel(streams), BorderLayout.NORTH);
        frame.add(buildCombinedPanel(streams, rangeControls), BorderLayout.CENTER);
        frame.add(buildDistancePanel(streams, rangeControls.b), BorderLayout.SOUTH);

        frame.pack();
        frame.setVisible(true);
    }

    /**
     * Builds the panel of simplified tracker displays.
     * Each tracker's stream is mapped to a comma-delimited string of its
     * tracker number, latitude and longitude, with altitude removed.
     * @param streams the GPS event streams, one per tracker.
     * @return a panel containing one display label per tracker.
     */
    private JPanel buildTrackerPanel(Stream<GpsEvent>[] streams) {
        JPanel panel = new JPanel(new GridLayout(NUM_TRACKERS, 1));
        panel.setBorder(BorderFactory.createTitledBorder("Tracker Feeds"));

        for (int i = 0; i < streams.length; i++) {
            Stream<String> simplified = streams[i].map(
                (GpsEvent ev) -> ev.name + "," + ev.latitude + "," + ev.longitude);

            Cell<String> display = withAutoClear(simplified, 3);

            SLabel label = new SLabel(display);
            panel.add(label);
        }

        return panel;
    }

    /**
     * Builds the latitude/longitude range control panel.
     * @return a pair of the controls panel and the cell holding the
     *         currently set {latBound, lonBound} range.
     */
    private Tuple2<JPanel, Cell<double[]>> buildRangeControls() {
        JPanel panel = new JPanel(new FlowLayout());

        STextField latField = new STextField("");
        STextField lonField = new STextField("");
        SButton setButton = new SButton("Set Range");

        Cell<String[]> fields = latField.text.lift(lonField.text,
            (lat, lon) -> new String[]{lat, lon});

        Stream<String[]> onSet = setButton.sClicked.snapshot(fields, (unit, f) -> f);

        Cell<String[]> rangeText = onSet.hold(new String[]{"", ""});
        Cell<String> rangeLabel = rangeText.map(r -> "Lat: " + r[0] + ", Lon: " + r[1]);
        Cell<double[]> range = rangeText.map(GpsGUI::parseRange);

        panel.add(new JLabel("Lat:"));
        panel.add(latField);
        panel.add(new JLabel("Lon:"));
        panel.add(lonField);
        panel.add(setButton);
        panel.add(new SLabel(rangeLabel));

        return new Tuple2<>(panel, range);
    }

    /**
     * Builds the panel showing a single display combining all tracker streams,
     * filtered to only show events within the currently set latitude/longitude
     * range, along with the range input controls.
     * @param streams the GPS event streams, one per tracker.
     * @param rangeControls the shared range controls panel and range cell.
     * @return a panel containing the filtered combined display label and range controls.
     */
    private JPanel buildCombinedPanel(Stream<GpsEvent>[] streams,
            Tuple2<JPanel, Cell<double[]>> rangeControls) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Combined Feed"));

        Stream<GpsEvent> merged = streams[0];
        for (int i = 1; i < streams.length; i++) {
            merged = merged.orElse(streams[i]);
        }

        Cell<double[]> range = rangeControls.b;

        Stream<Tuple2<GpsEvent, double[]>> snapped = merged.snapshot(range,
            (ev, r) -> new Tuple2<>(ev, r));

        Stream<GpsEvent> inRange = snapped
            .filter(t -> withinRange(t.a, t.b))
            .map(t -> t.a);

        Stream<String> simplified = inRange.map(
            (GpsEvent ev) -> ev.name + "," + ev.latitude + "," + ev.longitude);

        Cell<String> display = withAutoClear(simplified, 3);
        SLabel label = new SLabel(display);
        panel.add(label, BorderLayout.CENTER);
        panel.add(rangeControls.a, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Builds the panel showing distance travelled per tracker over the last
     * 5 minutes, using only events within the currently set range.
     * Altitude is converted from feet to meters, the distance between each
     * pair of consecutive in-range events is computed, and a sliding window
     * keeps only segments from the last 5 minutes when summing.
     * @param streams the GPS event streams, one per tracker.
     * @param range the cell holding the currently set {latBound, lonBound} range.
     * @return a panel containing one distance label per tracker.
     */
    private JPanel buildDistancePanel(Stream<GpsEvent>[] streams, Cell<double[]> range) {
        JPanel panel = new JPanel(new GridLayout(NUM_TRACKERS, 1));
        panel.setBorder(BorderFactory.createTitledBorder("Distance (last 5 min)"));

        for (int i = 0; i < streams.length; i++) {
            final int trackerNum = i;

            Stream<GpsEvent> inRangeStream = streams[i]
                .snapshot(range, (ev, r) -> new Tuple2<>(ev, r))
                .filter(t -> withinRange(t.a, t.b))
                .map(t -> t.a);

            Stream<GpsEvent> metersStream = inRangeStream.map(GpsGUI::withAltitudeInMeters);
            Cell<GpsEvent> lastPosition = metersStream.hold(null);

            Stream<Double> segments = metersStream.snapshot(lastPosition,
                GpsGUI::distanceBetween);

            Cell<LinkedList<double[]>> window = segments.accum(
                new LinkedList<>(), GpsGUI::addToWindow);

            Cell<String> distanceLabel = window.map(w ->
                "Tracker" + trackerNum + ": " + (long) Math.ceil(sumWindow(w)) + "m");

            SLabel label = new SLabel(distanceLabel);
            panel.add(label);
        }

        return panel;
    }

    /**
     * Adds a new distance segment to the sliding window, timestamped now,
     * then drops any segments older than the 5-minute window.
     * @param distance the new segment's distance in meters.
     * @param window the existing window of {timestamp, distance} entries.
     * @return the updated window, with stale entries removed.
     */
    private static LinkedList<double[]> addToWindow(double distance, LinkedList<double[]> window) {
        long now = System.currentTimeMillis();
        window.addLast(new double[]{now, distance});
        while (!window.isEmpty() && now - window.getFirst()[0] > WINDOW_MILLIS) {
            window.removeFirst();
        }
        return window;
    }

    /**
     * Sums the distance values currently held in a window.
     * @param window the window of {timestamp, distance} entries.
     * @return the total distance in meters.
     */
    private static double sumWindow(LinkedList<double[]> window) {
        double total = 0.0;
        for (double[] entry : window) {
            total += entry[1];
        }
        return total;
    }

    /**
     * Returns a copy of the given event with its altitude converted from
     * feet to meters.
     * @param ev the original event, with altitude in feet.
     * @return a new event with altitude in meters.
     */
    private static GpsEvent withAltitudeInMeters(GpsEvent ev) {
        return new GpsEvent(ev.name, ev.latitude, ev.longitude, ev.altitude * FEET_TO_METERS);
    }

    /**
     * Calculates the straight-line distance in meters between two events,
     * treating latitude/longitude degrees as flat (no Earth curvature) but
     * including the altitude difference.
     * @param current the current event.
     * @param previous the previous event, or null if there is none yet.
     * @return the distance in meters, or 0 if there is no previous event.
     */
    private static double distanceBetween(GpsEvent current, GpsEvent previous) {
        if (previous == null) {
            return 0.0;
        }
        double dLat = (current.latitude - previous.latitude) * METERS_PER_DEGREE;
        double dLon = (current.longitude - previous.longitude) * METERS_PER_DEGREE
            * Math.cos(Math.toRadians(current.latitude));
        double dAlt = current.altitude - previous.altitude;
        return Math.sqrt(dLat * dLat + dLon * dLon + dAlt * dAlt);
    }

    /**
     * Parses a {latitude, longitude} string pair into numeric bounds.
     * Unparsable or empty values default to the widest possible bound, so
     * the filter accepts everything until a valid range is set.
     * @param text the {latitude, longitude} strings from the range fields.
     * @return a {latBound, lonBound} array of parsed doubles.
     */
    private static double[] parseRange(String[] text) {
        return new double[]{
            parseOrDefault(text[0], 90.0),
            parseOrDefault(text[1], 180.0)
        };
    }

    /**
     * Parses a string to a double, falling back to a default on failure.
     * @param s the string to parse.
     * @param fallback the value to return if parsing fails.
     * @return the parsed double, or the fallback.
     */
    private static double parseOrDefault(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Checks whether an event falls within the current range bounds.
     * Provisional interpretation: the entered latitude/longitude act as
     * upper bounds. Revisit once the exact range semantics are confirmed.
     * @param ev the event to check.
     * @param bounds the {latBound, lonBound} values.
     * @return true if the event's latitude and longitude are within bounds.
     */
    private static boolean withinRange(GpsEvent ev, double[] bounds) {
        return ev.latitude <= bounds[0] && ev.longitude <= bounds[1];
    }

    /**
     * Combines a stream of display values with a timed clear signal, so that
     * the resulting cell resets to an empty string after a period of inactivity.
     * @param source the stream of display strings to watch.
     * @param seconds the number of seconds of inactivity before clearing.
     * @return a cell holding the latest display string, or "" once expired.
     */
    private Cell<String> withAutoClear(Stream<String> source, int seconds) {
        StreamSink<String> clearSink = new StreamSink<>();

        source.listen((String value) -> {
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                public void run() {
                    clearSink.send("");
                }
            }, seconds * 1000L);
        });

        return source.orElse(clearSink).hold("");
    }

    /**
     * Launches the GPS GUI application.
     * @param args command-line arguments (unused).
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(GpsGUI::new);
    }
}