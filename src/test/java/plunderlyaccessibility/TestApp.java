package plunderlyaccessibility;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Small Swing app for validating assistive-technology loading.
 *
 */
public class TestApp {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("AT Test");
            f.getAccessibleContext().setAccessibleName("AT Test");
            f.add(new JLabel("If you can read duty-reader.log, AT works."));
            f.setSize(360, 120);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
        // Leave the frame alive long enough for the assistive technology to load.
        Thread.sleep(4000);
        System.out.println("TestApp done.");
        System.exit(0);
    }
}
