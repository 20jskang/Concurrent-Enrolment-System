import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SubjectServer - starter skeleton.
 *
 * WHAT'S ALREADY DONE FOR YOU:
 *   - Command-line argument parsing and validation.
 *   - Reading the subject data file and decoding it as JSON.
 *
 * Use ProtocolMessage (see its own Javadoc) to build your
 * responses and parse incoming requests; you should not need to touch
 * SimpleJson directly.
 *
 * Usage:
 *   java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>
 */
public class SubjectServer {

    public static void main(String[] args) {

        // ==================== Argument parsing (provided) ====================
        if (args.length != 3) {
            System.err.println("Usage: java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>");
            System.exit(1);
            return;
        }

        int port = -1;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + args[0] + "': must be an integer between 0 and 65535.");
            System.exit(1);
            return;
        }

        String subjectDataFile = args[1];

        int delayMs = -1;
        try {
            delayMs = Integer.parseInt(args[2]);
            if (delayMs < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid artificial delay '" + args[2] + "': must be a non-negative integer, in milliseconds.");
            System.exit(1);
            return;
        }

        // ==================== Load the subject data file (partly provided) ====================
        SubjectRegistry.Store store =
                new SubjectRegistry.Store(SubjectRegistry.Store.pathFor(subjectDataFile));

        Map<String, Subject> subjects;
        if (store.exists()) {
            subjects = loadSubjects(store.getPath().toString(), "persistence file");
            store.adopt(subjects);
            System.out.println("Recovered " + subjects.size() + " subject(s) from " + store.getPath());
        } else {
            subjects = loadSubjects(subjectDataFile, "subject data file");
            System.out.println("Loaded " + subjects.size() + " subject(s) from " + subjectDataFile);
            try {
                store.initialise(subjects);
            } catch (IOException e) {
                System.err.println("Could not create persistence file '" + store.getPath()
                        + "': " + e.getMessage());
                System.exit(1);
                return;
            }
            System.out.println("Created persistence file " + store.getPath());
        }

        final SubjectRegistry registry = new SubjectRegistry(subjects, delayMs, store);

        final Sessions sessions = new Sessions();

        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            System.err.println("Could not listen on port " + port + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Listening on port " + serverSocket.getLocalPort()
                + " with " + subjects.size() + " subject(s), delay " + delayMs + "ms.");
        System.out.println("State file: " + store.getPath());
        System.out.println("Console commands: status | quit");

        final ServerSocket listener = serverSocket;
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(listener, registry, sessions);
            }
        }, "acceptor");
        acceptor.start();

        runConsole(serverSocket, sessions, acceptor, store);
    }

    /** Accepts client connections until the listening socket is closed, giving each its own thread. */
    private static void acceptLoop(ServerSocket serverSocket, SubjectRegistry registry,
                                   Sessions sessions) {
        int connectionCount = 0;
        try {
            while (true) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                } catch (SocketException e) {
                    System.out.println("Listening socket closed; no longer accepting.");
                    return;
                }

                connectionCount++;
                String name = "client-" + connectionCount;

                System.out.println("Client connection number " + connectionCount + " accepted:");
                System.out.println("  Remote Host: " + clientSocket.getInetAddress().getHostAddress());
                System.out.println("  Remote Port: " + clientSocket.getPort());
                System.out.println("  Local Port:  " + clientSocket.getLocalPort());

                ClientHandler handler = new ClientHandler(clientSocket, registry, sessions, name);
                Thread thread = new Thread(handler, name);

                if (!sessions.register(handler, thread)) {
                    handler.closeSocket();
                    return;
                }
                thread.start();
                System.out.println("  Named " + name + "; active connections: "
                        + sessions.activeCount());
            }
        } catch (IOException e) {
            System.err.println("Stopped accepting connections: " + e.getMessage());
        } finally {
            try {
                serverSocket.close();
            } catch (IOException e) {
            }
        }
    }

    /** Reads console commands from standard input: status prints the connection count, quit shuts down. */
    private static void runConsole(ServerSocket serverSocket, Sessions sessions,
                                   Thread acceptor, SubjectRegistry.Store store) {
        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = stdin.readLine()) != null) {
                String command = line.trim().toLowerCase();
                if (command.isEmpty()) {
                    continue;
                }
                if (command.equals("status")) {
                    System.out.println("Active client connections: " + sessions.activeCount());
                } else if (command.equals("quit") || command.equals("stop")) {
                    shutdown(serverSocket, sessions, acceptor, store);
                    return;
                } else {
                    System.out.println("Unrecognised command: " + command
                            + ". Known commands: status | quit");
                }
            }
            System.out.println("Console input closed; the server is still running.");
        } catch (IOException e) {
            System.err.println("Error reading from the server console: " + e.getMessage());
        }
    }

    /**
     * Stops accepting connections, waits for in-flight operations to finish,
     * then closes the client connections and exits.
     */
    private static void shutdown(ServerSocket serverSocket, Sessions sessions,
                                 Thread acceptor, SubjectRegistry.Store store) {
        System.out.println("Shutting down: no longer accepting new connections.");
        sessions.beginShutdown();
        try {
            serverSocket.close();
        } catch (IOException e) {
            System.err.println("Could not close the listening socket: " + e.getMessage());
        }

        System.out.println("Waiting for in-flight operations to finish...");
        sessions.awaitQuiescent();

        System.out.println("Final state is already durable at " + store.getPath()
                + " (persisted after every successful write).");

        sessions.closeAllAndJoin();
        try {
            acceptor.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Server stopped.");

        System.exit(0);
    }

    /** Tracks the live client connections and the number of operations currently in flight. */
    static final class Sessions {

        private final Object monitor = new Object();
        private final List<Session> live = new ArrayList<>();
        private int inFlight = 0;
        private boolean shuttingDown = false;

        /** Adds a new connection. Returns false if shutdown has already begun. */
        boolean register(ClientHandler handler, Thread thread) {
            synchronized (monitor) {
                if (shuttingDown) {
                    return false;
                }
                live.add(new Session(handler, thread));
                return true;
            }
        }

        /** Removes a connection whose handler has finished. */
        void deregister(ClientHandler handler) {
            synchronized (monitor) {
                for (Iterator<Session> it = live.iterator(); it.hasNext(); ) {
                    if (it.next().handler == handler) {
                        it.remove();
                        break;
                    }
                }
                monitor.notifyAll();
            }
        }

        /** Returns the number of active client connections. */
        int activeCount() {
            synchronized (monitor) {
                return live.size();
            }
        }

        /** Marks the start of an operation. Returns false if shutdown has already begun. */
        boolean beginOperation() {
            synchronized (monitor) {
                if (shuttingDown) {
                    return false;
                }
                inFlight++;
                return true;
            }
        }

        /** Marks the end of an operation. */
        void endOperation() {
            synchronized (monitor) {
                inFlight--;
                monitor.notifyAll();
            }
        }

        /** Flags that shutdown has started, so no further connection or operation is accepted. */
        void beginShutdown() {
            synchronized (monitor) {
                shuttingDown = true;
                monitor.notifyAll();
            }
        }

        /** Blocks until every in-flight operation has finished. */
        void awaitQuiescent() {
            synchronized (monitor) {
                while (inFlight > 0) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /** Closes every client socket and waits for the handler threads to finish. */
        void closeAllAndJoin() {
            List<Session> snapshot;
            synchronized (monitor) {
                snapshot = new ArrayList<>(live);
            }
            if (!snapshot.isEmpty()) {
                System.out.println("Closing " + snapshot.size() + " client connection(s).");
            }
            for (Session session : snapshot) {
                session.handler.closeSocket();
            }
            for (Session session : snapshot) {
                try {
                    session.thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** One live connection: its handler and the thread running it. */
        private static final class Session {
            final ClientHandler handler;
            final Thread thread;

            Session(ClientHandler handler, Thread thread) {
                this.handler = handler;
                this.thread = thread;
            }
        }
    }

    /**
     * Reads the subject data file, decodes it as JSON, and returns a
     * subjectCode -> Subject map. Exits the process with a clear error
     * message (and non-zero status) on any failure, per the Subject Data
     * File Format section.
     */
    private static Map<String, Subject> loadSubjects(String path, String label) {
        String text;
        try {
            text = new String(Files.readAllBytes(Paths.get(path)));
        } catch (java.nio.file.NoSuchFileException e) {
            System.err.println("Could not read " + label + " '" + path + "': no such file.");
            System.exit(1);
            return null; // unreachable, System.exit terminates the JVM
        } catch (java.nio.file.AccessDeniedException e) {
            System.err.println("Could not read " + label + " '" + path
                    + "': permission denied.");
            System.exit(1);
            return null; // unreachable
        } catch (IOException e) {
            System.err.println("Could not read " + label + " '" + path + "': "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        Object decoded;
        try {
            decoded = SimpleJson.decode(text);
        } catch (SimpleJson.JsonParseException e) {
            System.err.println("Invalid " + label + " '" + path + "': not valid JSON: " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        if (!(decoded instanceof List)) {
            System.err.println("Invalid " + label + " '" + path + "': must contain a JSON array at the top level.");
            System.exit(1);
            return null; // unreachable
        }

        Map<String, Subject> subjects = new HashMap<>();

        for (Object item : (List<?>) decoded) {
            if (!(item instanceof Map)) {
                System.err.println("Invalid " + label + " '" + path + "': every entry must be a JSON object.");
                System.exit(1);
                return null; // unreachable
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;

            Object rawSubjectCode = entry.get("subjectCode");
            if (!(rawSubjectCode instanceof String) || ((String) rawSubjectCode).trim().isEmpty()){
                fail(label, path, "Every entry needs a non-empty string. (Got: " + rawSubjectCode + ")");
            }

            String subjectCode = ((String) rawSubjectCode).trim();
            if (subjects.containsKey(subjectCode)) {
                fail(label, path, "Duplicate subjectCode - " + subjectCode + " - subjectCodes must be unique!");
            }

            Object rawCapacity = entry.get("capacity");
            if (!(rawCapacity instanceof Long)) {
                fail(label, path, "Subject - "  + subjectCode + " - must be a whole number. (Got: " + rawCapacity + ")");
            }

            long longCapacity = (Long) rawCapacity;
            if (longCapacity <= 0 || longCapacity > Integer.MAX_VALUE) {
                fail(label, path, "Subject - "  + subjectCode + " - must have a positive integer capacity. (Got: " + rawCapacity + ")");
            }
            int capacity = (int) longCapacity;

            Object rawStudentIds = entry.get("enrolledStudentIds");
            if (!(rawStudentIds instanceof List)) {
                fail(label, path, "Subject - " + subjectCode + " - enrolledstudentIds must be a JSON array (Got: " + rawStudentIds + ")");
            }

            List<?> enrolledStudentIdList = (List<?>) rawStudentIds;

            Set<String> enrolledStudentIds = new LinkedHashSet<>();
            for (int idIndex = 0; idIndex < enrolledStudentIdList.size(); idIndex++) {
                Object rawStudentId = enrolledStudentIdList.get(idIndex);
                if (!(rawStudentId instanceof String)) {
                    fail(label, path, "Subject - " + subjectCode + " - enrolledStudentIds[" + idIndex + "] must be a string. (Got: " + rawStudentId + ")");
                }
                String studentId = ((String) rawStudentId).trim();
                if (studentId.isEmpty()) {
                    fail(label, path, "Subject - " + subjectCode + " - enrolledStudentIds[" + idIndex + "] must not be an empty string.");
                }
                enrolledStudentIds.add(studentId);
            }
            if (enrolledStudentIds.size() > capacity) {
                fail(label, path, "Subject (" + subjectCode + "): " + enrolledStudentIds.size() + " students enrolled but capacity is set to " +  capacity);
            }
            subjects.put(subjectCode, new Subject(subjectCode, capacity, enrolledStudentIds));
        }
        return subjects;
    }

    /** Prints a validation error for the given file and exits with a non-zero status. */
    private static void fail(String label, String path, String problem) {
        System.err.println("Invalid " + label + " '" + path + "': " + problem);
        System.exit(1);
    }
}
