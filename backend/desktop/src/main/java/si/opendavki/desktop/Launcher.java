package si.opendavki.desktop;

import javafx.application.Application;

/**
 * A plain launcher class: the Java launcher refuses to start a main class that extends {@link Application} when JavaFX
 * is on the classpath instead of the module path. Delegating through this class keeps the packaged fat jar self-contained.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        boolean smoke = false;
        for (String arg : args) if (arg.equals("--smoke")) smoke = true;
        if (smoke) System.setProperty("opendavki.desktop.smoke", "true");
        Application.launch(DesktopApp.class, smoke ? new String[0] : args);
    }
}
