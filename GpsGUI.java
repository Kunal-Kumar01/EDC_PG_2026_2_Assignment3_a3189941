import java.awt.*;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.*;
import nz.sodium.*;
import swidgets.*;

/**
 * GUI that displays GPS tracker data processed via Sodium FRP.
 */
public class GpsGUI {

    private static final int NUM_TRACKERS = 10;

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

        frame.add(buildTrackerPanel(streams), BorderLayout.NORTH);
        frame.add(buildCombinedPanel(streams), BorderLayout.CENTER);

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
     * Builds the panel showing a single display combining all tracker streams,
     * filtered to only show events within the currently set latitude/longitude
     * range, along with the range input controls.
     * @param streams the GPS event streams, one per tracker.
     * @return a panel containing the filtered combined display label and range controls.
     */
    private JPanel buildCombinedPanel(Stream<GpsEvent>[] streams) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Combined Feed"));

        Stream<GpsEvent> merged = streams[0];
        for (int i = 1; i < streams.length; i++) {
            merged = merged.orElse(streams[i]);
        }

        STextField latField = new STextField("");
        STextField lonField = new STextField("");
        SButton setButton = new SButton("Set Range");

        Cell<String[]> fields = latField.text.lift(lonField.text,
            (lat, lon) -> new String[]{lat, lon});

        Stream<String[]> onSet = setButton.sClicked.snapshot(fields, (unit, f) -> f);

        Cell<String[]> rangeText = onSet.hold(new String[]{"", ""});
        Cell<String> rangeLabel = rangeText.map(r -> "Lat: " + r[0] + ", Lon: " + r[1]);
        Cell<double[]> range = rangeText.map(GpsGUI::parseRange);

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

        JPanel controls = new JPanel(new FlowLayout());
        controls.add(new JLabel("Lat:"));
        controls.add(latField);
        controls.add(new JLabel("Lon:"));
        controls.add(lonField);
        controls.add(setButton);
        controls.add(new SLabel(rangeLabel));
        panel.add(controls, BorderLayout.SOUTH);

        return panel;
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