
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
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    private static final Logger LOG = Logger.getLogger(SQLDesignerServer.class.getName());

    /**
     * Starts SQLDesignerServer
     *
     * @param args
     * @throws IOException
     * @throws URISyntaxException
     */
    public static void main(String[] args) throws IOException, URISyntaxException {
        final WebServer webServer = new WebServer();
        webServer.start();
    }

    /**
     * Representa os servidor web
     */
    private static class WebServer implements HttpHandler {

        private static final Set<String> MODELS_LIST = new HashSet<>();

        private static Path MODELS_DIR;

        private static Path BACKUP_DIR;

        static {
            try {
                final Path jarPath = Paths.get(SQLDesignerServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                MODELS_DIR = Paths.get(jarPath.getParent().toString(), "/data");
                BACKUP_DIR = Paths.get(jarPath.getParent().toString(), "/backup");

                LOG.log(Level.INFO, "DATA_DIR is now {0}", MODELS_DIR);

                // Create data directory
                Files.createDirectories(MODELS_DIR);

                // Create data bakcup directory
                Files.createDirectories(BACKUP_DIR);

                // Copy default model
                saveModelContent("default", Resources.getContent("/db/default"));
            } catch (IOException | URISyntaxException ex) {
                LOG.log(Level.SEVERE, null, ex);
                System.exit(-1);
            }

        }

        public void start() throws IOException, URISyntaxException {
            //
            updateModelsList();

            // Initialize server
            HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
            server.createContext("/", this);
            server.setExecutor(null);
            server.start();
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
                LOG.log(Level.SEVERE, "Erro inesperado", ex);

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

                he.getResponseHeaders().add("Content-type", Resources.getContentType("null.txt"));
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
            if (Resources.exists(path)) {
                final byte[] bytes = Resources.getContent(path);
                he.getResponseHeaders().add("Content-type", Resources.getContentType(path));
                he.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
                try (final OutputStream os = he.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                throw new NotFoundException();
            }
        }

        private void handleBackendApi(final HttpExchange he) throws IOException, SAXException, Exception {
            final Map<String, String> params = parseQueryString(he.getRequestURI().getQuery());
            final String action = params.get("action");
            final String name = params.get("keyword");
            switch (action) {
                case "list":
                    listModels(he);
                    break;
                case "save":
                    saveModel(he, validateAndSanitizeKeyword(name));
                    break;
                case "load":
                    loadModel(he, validateAndSanitizeKeyword(name));
                    break;
                default:
                    he.sendResponseHeaders(HttpURLConnection.HTTP_NOT_IMPLEMENTED, 0);
            }
        }

        private void listModels(final HttpExchange he) throws IOException {
            final StringBuilder sb = new StringBuilder();
            for (final String file : MODELS_LIST) {
                sb.append(file).append("\n");
            }

            final byte[] bytes = sb.toString().getBytes();
            he.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
            try (final OutputStream os = he.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void loadModel(final HttpExchange he, final String name) throws IOException {
            final Path filePath = MODELS_DIR.resolve("./" + name);
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

        private void saveModel(final HttpExchange he, final String name) throws IOException, SAXException {
            final String xml = validateAndCompactXml(he.getRequestBody());
            saveModelContent(name, xml.getBytes());
        }

        private static void saveModelContent(final String name, final byte[] content) throws IOException {
            final Path modelPath = Paths.get(MODELS_DIR.toString(), "/" + name);
            if (Files.exists(modelPath)) {
                // Check for update
                final byte[] oldContent = Files.readAllBytes(modelPath);
                if (Arrays.equals(oldContent, content)) {
                    LOG.log(Level.INFO, "Model doesnt have change, ignore saving: {0}", modelPath);
                    return;
                }

                // Make a backup
                final Path bakcupPath = Paths.get(BACKUP_DIR.toString(), "/" + name + "__" + (new Date()).getTime());
                Files.write(bakcupPath, oldContent);
                LOG.log(Level.INFO, "Model backup created: {0}", bakcupPath);
            }

            // Saving
            Files.write(modelPath, content);
            LOG.log(Level.INFO, "Model saved: {0}", modelPath);
        }

        private static String validateAndSanitizeKeyword(final String name) throws Exception {
            if (name == null) {
                throw new BadRequestException("QueryParam 'keyword' is required");
            }
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
            Files.walkFileTree(MODELS_DIR, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (pathMatcher.matches(path)) {
                        MODELS_LIST.add(path.getFileName().toString());
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
     *
     */
    private static class Resources {

        private static final Set<String> exists = new HashSet<>();

        private static final Map<String, String> MIME_TYPES = new HashMap<>();

        static {
            MIME_TYPES.put("txt", "text/plain");
            MIME_TYPES.put("css", "text/css");
            MIME_TYPES.put("html", "text/html");
            MIME_TYPES.put("json", "application/json");
            MIME_TYPES.put("xml", "application/xml");
            MIME_TYPES.put("xsl", "application/xml");
            MIME_TYPES.put("js", "application/javascript");
            MIME_TYPES.put("jpg", "image/jpeg");
            MIME_TYPES.put("png", "image/png");
            MIME_TYPES.put("gif", "image/gif");
        }

        public static boolean exists(final String path) {
            if (exists.contains(path)) {
                return true;
            }
            final InputStream is = Resources.class.getResourceAsStream(("/web/" + path).replaceAll("[/]+", "/"));
            if (is != null) {
                exists.add(path);
                return true;
            }
            return false;
        }

        public static byte[] getContent(final String path) throws IOException {
            ByteArrayOutputStream os;
            try (InputStream is = Resources.class.getResourceAsStream(("/web/" + path).replaceAll("[/]+", "/"))) {
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
            if (MIME_TYPES.containsKey(type)) {
                return MIME_TYPES.get(type);
            }
            return MIME_TYPES.get("txt");
        }

    }

    private static class BadRequestException extends Exception {

        private BadRequestException(final String message) {
            super(message);
        }
    }

    private static class NotFoundException extends Exception {

    }

}
