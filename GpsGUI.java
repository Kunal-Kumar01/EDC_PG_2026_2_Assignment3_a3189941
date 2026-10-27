import nz.sodium.*;
import swidgets.*;

import javax.swing.*;
import java.awt.*;
import java.util.Timer;
import java.util.TimerTask;

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