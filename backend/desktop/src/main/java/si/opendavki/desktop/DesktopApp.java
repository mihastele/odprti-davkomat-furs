package si.opendavki.desktop;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import si.opendavki.cli.CliPaths;
import si.opendavki.cli.web.WebServer;

/**
 * A native window around the same local Vue interface the browser uses.
 *
 * <p>JavaFX 21's WebView does not execute ES modules, so this shell loads the IIFE build produced by
 * {@code npm --prefix frontend run build:legacy}. Downloads are routed through {@link Bridge} because WebView has no
 * download manager of its own. The server still listens on loopback and remains the only owner of the ledger.
 */
public final class DesktopApp extends Application {
    private WebServer server;
    private Stage stage;

    @Override public void start(Stage primaryStage) throws Exception {
        this.stage = primaryStage;
        Path settings = CliPaths.envFile(null);
        Path schemas = CliPaths.schemaDir(null);
        String webOverride = System.getProperty("opendavki.desktop.web-dir");
        Path webDir = webOverride == null || webOverride.isBlank() ? null : Path.of(webOverride);
        server = new WebServer(0, webDir, settings, schemas);
        server.start();

        WebView web = new WebView();
        web.setContextMenuEnabled(false);
        WebEngine engine = web.getEngine();
        Bridge bridge = new Bridge(primaryStage);
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            // The JS window object is recreated by navigation; the bridge must be installed after every load.
            if (state == Worker.State.SUCCEEDED) {
                ((JSObject) engine.executeScript("window")).setMember("opendavkiDesktop", bridge);
            }
        });
        engine.load(server.url());
        smokeIfRequested(engine);

        MenuBar menuBar = menuBar(web);
        BorderPane root = new BorderPane(web);
        root.setTop(menuBar);
        primaryStage.setTitle("OpenDavki");
        primaryStage.setScene(new Scene(root, 1180, 860));
        primaryStage.setOnCloseRequest(e -> {
            if (server != null) server.close();
            Platform.exit();
        });
        primaryStage.show();
    }

    @Override public void stop() {
        if (server != null) server.close();
    }

    /**
     * Non-interactive verification hook used by the packaging smoke test: wait for the page, report whether the Vue
     * shell mounted and whether it reached the API, then exit. It is not part of normal startup.
     */
    private void smokeIfRequested(WebEngine engine) {
        if (!Boolean.getBoolean("opendavki.desktop.smoke")) return;
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.FAILED) {
                System.out.println("DESKTOP_SMOKE load-failed " + engine.getLoadWorker().getException());
                Platform.exit();
            }
        });
        PauseTransition checked = new PauseTransition(Duration.seconds(3));
        checked.setOnFinished(e -> {
            try {
                Object shell = engine.executeScript("document.querySelector('.topbar') ? 'ok' : 'missing'");
                Object errors = engine.executeScript("document.querySelectorAll('.banner.error').length");
                Object bridge = engine.executeScript("typeof window.opendavkiDesktop");
                Object saved = engine.executeScript("window.opendavkiDesktop.saveFile('smoke-download.txt', 'hello', 'text/plain')");
                Object pickedJson = engine.executeScript("window.opendavkiDesktop.pickFiles(true, '')");
                Object entries = engine.executeScript("document.body.innerText.includes('Ledger entries (1)') ? 'yes' : 'no'");
                Object filesShown = engine.executeScript("document.body.innerText.includes('opendavki-smoke.csv') ? 'yes' : 'no'");
                Object disabled = engine.executeScript("document.querySelector('button.primary') ? document.querySelector('button.primary').disabled : 'none'");
                Object text = engine.executeScript("(document.body && document.body.innerText || '').slice(0, 300)");
                System.out.println("DESKTOP_SMOKE shell=" + shell + " errors=" + errors + " bridge=" + bridge
                        + " saved=" + saved + " picked=" + pickedJson + " entries=" + entries + " files=" + filesShown
                        + " primaryDisabled=" + disabled + " text=" + text);
                boolean ok = "ok".equals(shell) && "object".equals(bridge) && "yes".equals(entries);
                Platform.exit();
                if (!ok) System.exit(1);
                return;
            } catch (RuntimeException ex) {
                System.out.println("DESKTOP_SMOKE javascript-failed " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            Platform.exit();
            System.exit(1);
        });
        PauseTransition picked = new PauseTransition(Duration.millis(600));
        picked.setOnFinished(e -> {
            try {
                engine.executeScript("document.querySelector('button.primary').click()");
                checked.playFromStart();
            } catch (RuntimeException ex) {
                System.out.println("DESKTOP_SMOKE import-click-failed " + ex.getMessage());
                Platform.exit();
            }
        });
        PauseTransition loaded = new PauseTransition(Duration.seconds(2));
        loaded.setOnFinished(e -> {
            try {
                // Exercise the file-picker bridge and the normal import button through the real page.
                engine.executeScript("document.querySelector('.dropzone').click()");
                picked.playFromStart();
            } catch (RuntimeException ex) {
                System.out.println("DESKTOP_SMOKE picker-failed " + ex.getMessage());
                Platform.exit();
            }
        });
        loaded.play();
    }

    private MenuBar menuBar(WebView web) {
        MenuItem reload = new MenuItem("Reload");
        reload.setOnAction(e -> web.getEngine().reload());
        MenuItem browser = new MenuItem("Open in browser");
        browser.setOnAction(e -> openBrowser(server.url()));
        MenuItem quit = new MenuItem("Quit");
        quit.setOnAction(e -> {
            if (server != null) server.close();
            Platform.exit();
        });
        Menu file = new Menu("File", null, browser, quit);
        Menu view = new Menu("View", null, reload);
        return new MenuBar(file, view);
    }

    private static void openBrowser(String url) {
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (IOException | RuntimeException e) {
            // The address is visible in the window title/menu only in the browser shell; the WebView remains usable.
        }
    }

    /** The JavaScript API used by {@code download()} in the web interface. */
    public static final class Bridge {
        private final Stage stage;

        Bridge(Stage stage) { this.stage = stage; }

        /**
         * WebView does not provide a working file input chooser. The page calls this instead and gets a JSON array of
         * name/base64/size objects, which it can upload to the local server exactly like browser File objects.
         */
        public String pickFiles(boolean multiple, String accept) throws IOException {
            if (Boolean.getBoolean("opendavki.desktop.smoke")) {
                byte[] sample = ("type,date,broker,account,id,isin,symbol,name,asset_class,quantity,price,currency\n"
                        + "BUY,2023-01-02,smoke,main,,US0378331005,AAPL,Apple,STOCK,1,100,EUR\n")
                        .getBytes(StandardCharsets.UTF_8);
                return "[{\"name\":\"opendavki-smoke.csv\",\"base64\":\""
                        + java.util.Base64.getEncoder().encodeToString(sample) + "\",\"size\":" + sample.length + "}]";
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose statement or project files");
            if (accept != null && !accept.isBlank()) {
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OpenDavki files", accept));
            }
            List<File> chosen = multiple ? chooser.showOpenMultipleDialog(stage) : single(chooser.showOpenDialog(stage));
            if (chosen == null || chosen.isEmpty()) return "[]";
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < chosen.size(); i++) {
                File file = chosen.get(i);
                if (file.length() > 64L * 1024 * 1024) throw new IOException(file.getName() + " is larger than 64 MB");
                if (i > 0) json.append(',');
                json.append("{\"name\":\"").append(jsonEscape(file.getName())).append("\",\"base64\":\"")
                        .append(java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())))
                        .append("\",\"size\":").append(file.length()).append('}');
            }
            return json.append(']').toString();
        }

        private static List<File> single(File file) {
            return file == null ? List.of() : List.of(file);
        }

        private static String jsonEscape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        /** Shows a save dialog and writes the text. Returns the path, or the string {@code cancelled}. */
        public String saveFile(String name, String text, String type) throws IOException {
            if (Boolean.getBoolean("opendavki.desktop.smoke")) {
                // The packaging smoke test cannot click a native save dialog; exercise the same writer without one.
                Path dir = Path.of(System.getProperty("java.io.tmpdir"), "opendavki-desktop-smoke");
                Files.createDirectories(dir);
                Path target = dir.resolve(name == null || name.isBlank() ? "download.txt" : name);
                Files.writeString(target, text == null ? "" : text, StandardCharsets.UTF_8);
                return target.toString();
            }
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName(name == null || name.isBlank() ? "opendavki-download" : name);
            File chosen = chooser.showSaveDialog(stage);
            if (chosen == null) return "cancelled";
            Files.writeString(chosen.toPath(), text == null ? "" : text, StandardCharsets.UTF_8);
            return chosen.getAbsolutePath();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
