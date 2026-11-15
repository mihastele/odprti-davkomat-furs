package si.opendavki.cli;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * A minimal system-tray icon so a packaged, console-less launch has a visible way to reopen the page and to stop the
 * server. Not every desktop supports a tray; callers fall back to the console instructions.
 */
final class Tray implements AutoCloseable {
    private final TrayIcon icon;

    private Tray(Runnable open, Runnable quit) throws AWTException {
        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("Open OpenDavki");
        openItem.addActionListener(e -> open.run());
        MenuItem quitItem = new MenuItem("Quit OpenDavki");
        quitItem.addActionListener(e -> quit.run());
        menu.add(openItem);
        menu.addSeparator();
        menu.add(quitItem);

        icon = new TrayIcon(image(), "OpenDavki (local interface)", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> open.run());
        SystemTray.getSystemTray().add(icon);
    }

    /** Returns {@code null} when the desktop has no usable tray. */
    static Tray install(Runnable open, Runnable quit) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return null;
        try {
            return new Tray(open, quit);
        } catch (AWTException | RuntimeException e) {
            return null;
        }
    }

    private static BufferedImage image() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x1f5f4a));
            g.fillRoundRect(0, 0, 16, 16, 5, 5);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(4, 5, 4, 11);
            g.drawLine(4, 5, 12, 5);
            g.drawLine(4, 8, 10, 8);
            g.drawLine(4, 11, 12, 11);
        } finally {
            g.dispose();
        }
        return image;
    }

    @Override public void close() {
        SystemTray.getSystemTray().remove(icon);
    }
}
