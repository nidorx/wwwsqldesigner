
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
public class JavaFile {

    private static final Set<String> FILES = new HashSet<>();

    private static final Logger LOG = Logger.getLogger(JavaFile.class.getName());

    private static Path MODELS_PATH;

    static {
        try {
            final Path jarPath = Paths.get(JavaFile.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            MODELS_PATH = Paths.get(jarPath.getParent().toString(), "/data");
        } catch (URISyntaxException ex) {
            LOG.log(Level.SEVERE, null, ex);
            System.exit(-1);
        }
    }

    /**
     * @param args the command line arguments
     * @throws java.io.IOException
     * @throws java.net.URISyntaxException
     */
    public static void main(String[] args) throws IOException, URISyntaxException {
        updateFilesList();
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/", new Handler());

        server.setExecutor(null); // creates a default executor
        server.start();
    }

    static class Handler implements HttpHandler {

        @Override
        public void handle(HttpExchange he) throws IOException {

            try {
                Map<String, String> params = parseQueryString(he.getRequestURI().getQuery());
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
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, null, ex);

                final StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                byte[] stack = sw.toString().getBytes();

                final int statusCode;
                if (ex instanceof BadRequestException) {
                    statusCode = HttpURLConnection.HTTP_BAD_REQUEST;
                } else {
                    statusCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
                }
                he.sendResponseHeaders(statusCode, stack.length);
                try (final OutputStream os = he.getResponseBody()) {
                    os.write(stack);
                }
            } finally {
                he.close();
            }

        }

        private void listModels(final HttpExchange he) throws IOException {
            final StringBuilder sb = new StringBuilder();
            for (final String file : FILES) {
                sb.append(file).append("\n");
            }

            final byte[] bytes = sb.toString().getBytes();
            he.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
            try (final OutputStream os = he.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void loadModel(final HttpExchange he, final String name) throws IOException {
            final Path filePath = MODELS_PATH.resolve("./" + name);
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

    }

    private static void updateFilesList() throws IOException, URISyntaxException {

        final PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*");
        Files.walkFileTree(MODELS_PATH, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (pathMatcher.matches(path)) {
                    FILES.add(path.getFileName().toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }
        });
    }

    private static class BadRequestException extends Exception {

        private BadRequestException(final String message) {
            super(message);
        }
    }
}
