
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

/**
 *
 * @author Alex Rodin<contato@alexrodin.info>
 */
public class SQLDesignerServer {

    private static final Logger logger = Logger.getLogger("www-sql");

    // Real path of jar file
    private static final String REAL_BASE_PATH;

    private static long LAST_MODIFIED_TIME = (new Date()).getTime();

    static {

        // Default language for output
        Locale.setDefault(new Locale("en", "US"));

        // Logger formating
        System.setProperty(
                "java.util.logging.SimpleFormatter.format",
                "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$-6s %5$s%6$s%n"
        );

        URI thatClassUri = null;
        try {
            thatClassUri = SQLDesignerServer.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (URISyntaxException ex) {
            logger.log(Level.SEVERE, "Unable to determine base path", ex);
            System.exit(-1);
        }
        REAL_BASE_PATH = Paths.get(thatClassUri).getParent().toString();

        // Last Modified Time, for caching
        try {
            final FileTime lastModifiedTime = Files.getLastModifiedTime(Paths.get(thatClassUri));
            if (lastModifiedTime != null) {
                LAST_MODIFIED_TIME = lastModifiedTime.toMillis();
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Unable to determine lastModifiedTime", ex);
        }

        // Log to file
        try {
            final FileHandler logfile = new FileHandler(Paths.get(REAL_BASE_PATH, "/www-sql-designer.log").toString());
            logfile.setFormatter(new SimpleFormatter());
            logger.addHandler(logfile);
        } catch (IOException | SecurityException ex) {
            logger.log(Level.SEVERE, "Unable to create logfile", ex);
        }

        // Allows debug
        // java -jar -Ddebugmode=true www-sql-designer-version.jar
        if ("true".equals(System.getProperty("debugmode"))) {
            logger.setLevel(Level.ALL);
        }
    }

    /**
     * Starts SQLDesignerServer
     *
     * @param args
     * @throws IOException
     * @throws URISyntaxException
     */
    public static void main(String[] args) throws IOException, URISyntaxException {
        new WebServer().start();
    }

    /**
     * Server all static content and API endpoints
     */
    private static class WebServer implements HttpHandler {

        private static final Set<String> diagramsList = new HashSet<>();

        private static Path diagramsPath;

        private static Path snapshotsPath;

        public WebServer() throws URISyntaxException, IOException {
            diagramsPath = Paths.get(REAL_BASE_PATH, "/diagrams");
            snapshotsPath = Paths.get(REAL_BASE_PATH, "/snapshots");

            // Create diagrams directory (if not exists)
            Files.createDirectories(diagramsPath);

            // Create snapthots directory (if not exists)
            Files.createDirectories(snapshotsPath);

            logger.log(Level.INFO, "Diagram path: {0}", diagramsPath);
            logger.log(Level.INFO, "Snapshots path: {0}", diagramsPath);

            // Create default diagram
            saveDiagramContent("default", WebResources.getContent("/db/default"));

            // Generate a list of all diagrams
            updateModelsList();
        }

        public void start() throws IOException, URISyntaxException {
            // java -jar -Dport=8000
            final String portProperty = System.getProperty("port");
            final String port;
            if (portProperty != null) {
                port = portProperty;
            } else {
                port = "8000";
            }

            logger.log(Level.INFO, "Starting server: http://localhost:{0}", port);

            // Initialize server
            HttpServer server = HttpServer.create(new InetSocketAddress(Integer.valueOf(port)), 0);
            server.createContext("/", this);
            server.setExecutor(null);
            server.start();

            logger.log(Level.INFO, "Startup completed");
        }

        @Override
        public void handle(final HttpExchange he) throws IOException {

            try {

                final String path = he.getRequestURI().getPath();
                if (path.equalsIgnoreCase("/backend")) {
                    handleBackendApi(he);
                } else {
                    handleStaticContent(he);
                }

            } catch (Exception ex) {
                logger.log(Level.SEVERE, "An unexpected error has occurred", ex);

                final StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                byte[] stack = sw.toString().getBytes();

                final int statusCode;
                if (ex instanceof BadRequestException) {
                    statusCode = HttpURLConnection.HTTP_BAD_REQUEST;
                } else if (ex instanceof NotFoundException) {
                    statusCode = HttpURLConnection.HTTP_NOT_FOUND;
                } else {
                    statusCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
                }

                he.getResponseHeaders().add("Content-type", WebResources.getContentType("null.txt"));
                he.sendResponseHeaders(statusCode, stack.length);
                try (final OutputStream os = he.getResponseBody()) {
                    os.write(stack);
                }
            } finally {
                he.close();
            }
        }

        private void handleStaticContent(final HttpExchange he) throws IOException, NotFoundException {
            String path = he.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            if (WebResources.exists(path)) {
                final byte[] bytes = WebResources.getContent(path);
                he.getResponseHeaders().add("Content-type", WebResources.getContentType(path));
                he.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
                try (final OutputStream os = he.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                throw new NotFoundException("Static file does not exist: '" + path + "'");
            }
        }

        private void handleBackendApi(final HttpExchange he) throws IOException, SAXException, Exception {
            final Map<String, String> params = parseQueryString(he.getRequestURI().getQuery());
            final String action = params.get("action");
            final String name = params.get("keyword");
            switch (action) {
                case "list":
                    doListModels(he);
                    break;
                case "save":
                    if (name == null) {
                        throw new BadRequestException("QueryParam 'keyword' is required");
                    }
                    doSaveDiagram(he, sanitizeDiagramName(name));
                    break;
                case "load":
                    if (name == null) {
                        throw new BadRequestException("QueryParam 'keyword' is required");
                    }
                    doLoadModel(he, sanitizeDiagramName(name));
                    break;
                default:
                    he.sendResponseHeaders(HttpURLConnection.HTTP_NOT_IMPLEMENTED, 0);
            }
        }

        private void doListModels(final HttpExchange he) throws IOException {
            final StringBuilder sb = new StringBuilder();
            for (final String file : diagramsList) {
                sb.append(file).append("\n");
            }

            final byte[] bytes = sb.toString().getBytes();
            he.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
            try (final OutputStream os = he.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void doLoadModel(final HttpExchange he, final String name) throws IOException {
            final Path filePath = diagramsPath.resolve("./" + name);
            if (Files.exists(filePath)) {
                he.getResponseHeaders().add("Content-type", "text/xml");

                final byte[] bytes = Files.readAllBytes(filePath);
                he.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
                try (final OutputStream os = he.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                he.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, 0);
            }
        }

        private void doSaveDiagram(final HttpExchange he, final String name) throws IOException, SAXException {
            final String xml = validateAndCompactXml(he.getRequestBody());
            saveDiagramContent(name, xml.getBytes());
        }

        private static void saveDiagramContent(final String name, final byte[] content) throws IOException {
            final Path modelPath = Paths.get(diagramsPath.toString(), "/" + name);
            if (Files.exists(modelPath)) {
                // Check for update
                final byte[] oldContent = Files.readAllBytes(modelPath);
                if (Arrays.equals(oldContent, content)) {
                    logger.log(Level.INFO, "Diagram doesnt have change: {0}", modelPath);
                    return;
                }

                // Make a backup
                final Path bakcupPath = Paths.get(snapshotsPath.toString(), "/" + name + "__" + (new Date()).getTime());
                Files.write(bakcupPath, oldContent);
                logger.log(Level.INFO, "Diagram snapshot created: {0}", bakcupPath);
            }

            // Saving
            Files.write(modelPath, content);
            logger.log(Level.INFO, "Diagram saved: {0}", modelPath);
        }

        private static String sanitizeDiagramName(final String name) throws Exception {
            return name.replaceAll("[^a-zA-Z0-9_-]", "");
        }

        private static String validateAndCompactXml(final InputStream body) throws SAXException, IOException {
            final XMLReader parser = XMLReaderFactory.createXMLReader();
            parser.setContentHandler(new DefaultHandler());
            parser.parse(new InputSource(body));
            String xml = parser.toString();

            // Remove empty line
            final StringBuilder sb = new StringBuilder();
            for (String part : xml.split("\n")) {
                final String line = part.trim();
                if (!line.isEmpty()) {
                    sb.append(line);
                }
            }
            return sb.toString();
        }

        private static Map<String, String> parseQueryString(final String query) {
            final Map<String, String> result = new HashMap<>();
            for (final String param : query.split("&")) {
                final String pair[] = param.split("=");
                if (pair.length > 1) {
                    result.put(pair[0], pair[1]);
                } else {
                    result.put(pair[0], "");
                }
            }
            return result;
        }

        private static void updateModelsList() throws IOException, URISyntaxException {

            final PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*");
            Files.walkFileTree(diagramsPath, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (pathMatcher.matches(path)) {
                        diagramsList.add(path.getFileName().toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.TERMINATE;
                }
            });
        }

    }

    /**
     * Manage web resources
     */
    private static class WebResources {

        private static final Set<String> exists = new HashSet<>();

        private static final Map<String, String> mimeTypes = new HashMap<>();

        static {
            mimeTypes.put("txt", "text/plain");
            mimeTypes.put("css", "text/css");
            mimeTypes.put("html", "text/html");
            mimeTypes.put("json", "application/json");
            mimeTypes.put("xml", "application/xml");
            mimeTypes.put("xsl", "application/xml");
            mimeTypes.put("js", "application/javascript");
            mimeTypes.put("jpg", "image/jpeg");
            mimeTypes.put("png", "image/png");
            mimeTypes.put("gif", "image/gif");
        }

        public static boolean exists(final String path) {
            if (exists.contains(path)) {
                return true;
            }
            final InputStream is = WebResources.class.getResourceAsStream(("/web/" + path).replaceAll("[/]+", "/"));
            if (is != null) {
                exists.add(path);
                return true;
            }
            return false;
        }

        public static byte[] getContent(final String path) throws IOException {
            ByteArrayOutputStream os;
            try (InputStream is = WebResources.class.getResourceAsStream(("/web/" + path).replaceAll("[/]+", "/"))) {
                os = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int line;
                while ((line = is.read(buffer)) != -1) {
                    os.write(buffer, 0, line);
                }
            }
            os.flush();
            os.close();
            return os.toByteArray();
        }

        private static String getContentType(final String path) {
            final String type = path.replaceAll("^(.*).(css|jpg|png|gif|html|js|xml|xsl)$", "$2");
            if (mimeTypes.containsKey(type)) {
                return mimeTypes.get(type);
            }
            return mimeTypes.get("txt");
        }
    }

    private static class BadRequestException extends Exception {

        private BadRequestException(final String message) {
            super(message);
        }
    }

    private static class NotFoundException extends Exception {

        private NotFoundException(final String message) {
            super(message);
        }
    }

}
