import nz.sodium.*;
import swidgets.*;

import javax.swing.*;
import java.awt.*;

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

            Cell<String> display = simplified.hold("");

            SLabel label = new SLabel(display);
            panel.add(label);
        }

        return panel;
    }

    /**
     * Launches the GPS GUI application.
     * @param args command-line arguments (unused).
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(GpsGUI::new);
    }
}