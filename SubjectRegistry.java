import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds the subjects and applies the five operations to them under the appropriate locks. */
public class SubjectRegistry {

    private final Map<String, Subject> subjects;
    private final int delayMs;
    private final Store store;

    public SubjectRegistry(Map<String, Subject> subjects, int delayMs, Store store) {
        this.subjects = subjects;
        this.delayMs = delayMs;
        this.store = store;
    }

    /** Returns the subject's capacity, enrolled count and student IDs, or NOT_FOUND. */
    public ProtocolMessage query(String subjectCode) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            sleep();
            return notFound(subjectCode);
        }

        subject.getLock().readLock().lock();
        try {
            sleep();
            ArrayList<String> enrolled = new ArrayList<>(subject.getEnrolledStudentIds());
            return ProtocolMessage.successResponse(
                    ProtocolMessage.queryData(subject.getSubjectCode(),
                                              subject.getCapacity(),
                                              enrolled.size(),
                                              enrolled));
        } finally {
            subject.getLock().readLock().unlock();
        }
    }

    /** Enrols a student, or returns NOT_FOUND, DUPLICATE_ENROLMENT or FULL. */
    public ProtocolMessage enrol(String subjectCode, String studentId) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            sleep();
            return notFound(subjectCode);
        }

        subject.getLock().writeLock().lock();
        try {
            sleep();
            Set<String> enrolled = subject.getEnrolledStudentIds();

            if (enrolled.contains(studentId)) {
                return ProtocolMessage.errorResponse(
                        ProtocolMessage.STATUS_DUPLICATE_ENROLMENT,
                        "Student " + studentId + " is already enrolled in " + subjectCode + ".");
            }
            if (enrolled.size() >= subject.getCapacity()) {
                return ProtocolMessage.errorResponse(
                        ProtocolMessage.STATUS_FULL,
                        "Subject " + subjectCode + " is at capacity (" + subject.getCapacity() + ").");
            }

            List<String> next = new ArrayList<>(enrolled);
            next.add(studentId);
            try {
                store.save(subjectCode, subject.getCapacity(), next);
            } catch (IOException e) {
                return persistenceFailed(e);
            }

            enrolled.add(studentId);
            return ProtocolMessage.successResponse();
        } finally {
            subject.getLock().writeLock().unlock();
        }
    }

    /** Withdraws a student, or returns NOT_FOUND or NOT_ENROLLED. */
    public ProtocolMessage withdraw(String subjectCode, String studentId) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            sleep();
            return notFound(subjectCode);
        }

        subject.getLock().writeLock().lock();
        try {
            sleep();
            Set<String> enrolled = subject.getEnrolledStudentIds();

            if (!enrolled.contains(studentId)) {
                return ProtocolMessage.errorResponse(
                        ProtocolMessage.STATUS_NOT_ENROLLED,
                        "Student " + studentId + " is not enrolled in " + subjectCode + ".");
            }

            List<String> next = new ArrayList<>(enrolled);
            next.remove(studentId);
            try {
                store.save(subjectCode, subject.getCapacity(), next);
            } catch (IOException e) {
                return persistenceFailed(e);
            }

            enrolled.remove(studentId);
            return ProtocolMessage.successResponse();
        } finally {
            subject.getLock().writeLock().unlock();
        }
    }

    /** Moves a student between two subjects, locking both in a fixed order so a transfer cannot deadlock. */
    public ProtocolMessage transfer(String fromSubjectCode, String toSubjectCode, String studentId) {
        Subject from = subjects.get(fromSubjectCode);
        if (from == null) {
            sleep();
            return notFound(fromSubjectCode);
        }
        Subject to = subjects.get(toSubjectCode);
        if (to == null) {
            sleep();
            return notFound(toSubjectCode);
        }
        if (fromSubjectCode.equals(toSubjectCode)) {
            sleep();
            return ProtocolMessage.errorResponse(
                    ProtocolMessage.STATUS_INVALID_REQUEST,
                    "Cannot transfer a student from " + fromSubjectCode + " to itself.");
        }

        Subject first  = fromSubjectCode.compareTo(toSubjectCode) < 0 ? from : to;
        Subject second = fromSubjectCode.compareTo(toSubjectCode) < 0 ? to : from;

        first.getLock().writeLock().lock();
        try {
            second.getLock().writeLock().lock();
            try {
                sleep();
                Set<String> fromEnrolled = from.getEnrolledStudentIds();
                Set<String> toEnrolled = to.getEnrolledStudentIds();

                if (!fromEnrolled.contains(studentId)) {
                    return ProtocolMessage.errorResponse(
                            ProtocolMessage.STATUS_NOT_ENROLLED,
                            "Student " + studentId + " is not enrolled in " + fromSubjectCode + ".");
                }
                if (toEnrolled.contains(studentId)) {
                    return ProtocolMessage.errorResponse(
                            ProtocolMessage.STATUS_DUPLICATE_ENROLMENT,
                            "Student " + studentId + " is already enrolled in " + toSubjectCode + ".");
                }
                if (toEnrolled.size() >= to.getCapacity()) {
                    return ProtocolMessage.errorResponse(
                            ProtocolMessage.STATUS_FULL,
                            "Subject " + toSubjectCode + " is at capacity (" + to.getCapacity() + ").");
                }

                List<String> nextFrom = new ArrayList<>(fromEnrolled);
                nextFrom.remove(studentId);
                List<String> nextTo = new ArrayList<>(toEnrolled);
                nextTo.add(studentId);
                try {
                    store.save(fromSubjectCode, from.getCapacity(), nextFrom,
                               toSubjectCode, to.getCapacity(), nextTo);
                } catch (IOException e) {
                    return persistenceFailed(e);
                }

                fromEnrolled.remove(studentId);
                toEnrolled.add(studentId);
                return ProtocolMessage.successResponse();
            } finally {
                second.getLock().writeLock().unlock();
            }
        } finally {
            first.getLock().writeLock().unlock();
        }
    }

    /** Sets a new capacity, rejecting one that is not positive or is below the enrolled count. */
    public ProtocolMessage updateCapacity(String subjectCode, int newCapacity) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            sleep();
            return notFound(subjectCode);
        }

        subject.getLock().writeLock().lock();
        try {
            sleep();

            if (newCapacity <= 0) {
                return ProtocolMessage.errorResponse(
                        ProtocolMessage.STATUS_INVALID_REQUEST,
                        "newCapacity must be a positive integer (got " + newCapacity + ").");
            }
            int enrolledCount = subject.getEnrolledCount();
            if (newCapacity < enrolledCount) {
                return ProtocolMessage.errorResponse(
                        ProtocolMessage.STATUS_INVALID_REQUEST,
                        "newCapacity " + newCapacity + " is below the current enrolled count of "
                                + enrolledCount + " for " + subjectCode + ".");
            }

            try {
                store.save(subjectCode, newCapacity, subject.getEnrolledStudentIds());
            } catch (IOException e) {
                return persistenceFailed(e);
            }

            subject.setCapacity(newCapacity);
            return ProtocolMessage.successResponse();
        } finally {
            subject.getLock().writeLock().unlock();
        }
    }

    /** Builds the NOT_FOUND response for an unknown subject code. */
    private ProtocolMessage notFound(String subjectCode) {
        return ProtocolMessage.errorResponse(
                ProtocolMessage.STATUS_NOT_FOUND,
                "No such subject: " + subjectCode + ".");
    }

    /** Reports a failed disk write and builds the ERROR response for it. */
    private ProtocolMessage persistenceFailed(IOException e) {
        System.err.println("Persistence failure: " + e.getMessage());
        return ProtocolMessage.errorResponse(
                ProtocolMessage.STATUS_ERROR,
                "Could not persist the change; the operation was not applied.");
    }

    /** Sleeps for the artificial delay, if one was configured. */
    private void sleep() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writes the current state to disk after every successful write, so a restart can recover it. */
    public static final class Store {

        private final Path path;
        private final Path temp;
        private final Object lock = new Object();

        private final Map<String, Map<String, Object>> mirror = new LinkedHashMap<>();

        public Store(Path path) {
            this.path = path;
            this.temp = path.resolveSibling(path.getFileName() + ".tmp");
        }

        /** Returns the state file path derived from the subject data file's name. */
        public static Path pathFor(String subjectDataFile) {
            Path data = Paths.get(subjectDataFile).toAbsolutePath();
            String name = data.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = (dot > 0) ? name.substring(0, dot) : name;
            return data.resolveSibling(base + ".state.json");
        }

        /** Returns the path of the state file. */
        public Path getPath() {
            return path;
        }

        /** Returns whether a state file is already on disk. */
        public boolean exists() {
            return Files.isRegularFile(path);
        }

        /** Writes the first state file from the subjects just loaded. */
        public void initialise(Map<String, Subject> subjects) throws IOException {
            synchronized (lock) {
                fillMirror(subjects);
                writeFile(mirror.values());
            }
        }

        /** Takes the recovered subjects as the current state, without rewriting the file. */
        public void adopt(Map<String, Subject> subjects) {
            synchronized (lock) {
                fillMirror(subjects);
            }
        }

        /** Persists one changed subject. */
        public void save(String subjectCode, int capacity, Collection<String> enrolledStudentIds)
                throws IOException {
            Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
            changes.put(subjectCode, entry(subjectCode, capacity, enrolledStudentIds));
            commit(changes);
        }

        /** Persists both subjects changed by a transfer, in a single write. */
        public void save(String fromSubjectCode, int fromCapacity, Collection<String> fromIds,
                         String toSubjectCode, int toCapacity, Collection<String> toIds)
                throws IOException {
            Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
            changes.put(fromSubjectCode, entry(fromSubjectCode, fromCapacity, fromIds));
            changes.put(toSubjectCode, entry(toSubjectCode, toCapacity, toIds));
            commit(changes);
        }

        /** Applies the changes to the in-memory copy and writes the whole state file out. */
        private void commit(Map<String, Map<String, Object>> changes) throws IOException {
            synchronized (lock) {
                Map<String, Map<String, Object>> next = new LinkedHashMap<>(mirror);
                next.putAll(changes);
                writeFile(next.values());
                mirror.clear();
                mirror.putAll(next);
            }
        }

        /** Writes the state to a temporary file, syncs it to disk, then renames it over the real one. */
        private void writeFile(Collection<Map<String, Object>> entries) throws IOException {
            byte[] bytes = SimpleJson.encode(new ArrayList<Object>(entries))
                    .getBytes(StandardCharsets.UTF_8);
            FileOutputStream out = new FileOutputStream(temp.toFile());
            try {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
        }

        /** Rebuilds the in-memory copy of the file from the given subjects. */
        private void fillMirror(Map<String, Subject> subjects) {
            mirror.clear();
            for (Subject subject : subjects.values()) {
                mirror.put(subject.getSubjectCode(),
                           entry(subject.getSubjectCode(), subject.getCapacity(),
                                 subject.getEnrolledStudentIds()));
            }
        }

        /** Builds the JSON object for one subject. */
        private static Map<String, Object> entry(String subjectCode, int capacity,
                                                 Collection<String> enrolledStudentIds) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("subjectCode", subjectCode);
            e.put("capacity", (long) capacity);
            e.put("enrolledStudentIds", new ArrayList<Object>(enrolledStudentIds));
            return e;
        }
    }
}
