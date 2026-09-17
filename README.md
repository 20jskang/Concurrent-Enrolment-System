# COMP90015 Assignment 1: Provided Scaffolding

Grade: 15/15 (H1)

This is the starter code referenced in the "Provided Scaffolding" section of the
assignment specification. Read this whole file before you start: it explains
exactly what's done for you and what isn't.

## Files

| File | What it is | Do you need to modify it?                |
|---|---|------------------------------------------|
| `SubjectServer.java` | Server skeleton | **Yes, this is most of the assignment.** |
| `SubjectClient.java` | Client skeleton | **Yes, the networking half of it.**      |
| `Subject.java` | Plain data record for one subject | Optional                                 |
| `ProtocolMessage.java` | Builds/parses the JSON protocol messages | No                                       |
| `ProtocolException.java` | Thrown on malformed/missing protocol fields | No                                       |
| `SimpleJson.java` | Internal JSON encoder/decoder used by ProtocolMessage | **You do not need to touch this**        |
| `subjects-sample.json` | A sample subject data file to test against | Use as-is, or write your own             |
| `build.sh` | Optional convenience script: compiles and packages both jars | No                                       |

No build tool (Maven/Gradle) and no external libraries are needed or used
anywhere in this scaffolding; everything compiles with plain `javac`, and
jars are built with the standard `jar` tool that ships with the JDK.

## Compiling, packaging, and running

Your final submission must be two runnable jars, `SubjectServer.jar` and
`SubjectClient.jar` (see the Submission section of the spec), so get in the
habit of building them that way from the start rather than only running the
java files directly.

A `build.sh` script that runs the necessary commands to generate your jars is
included for convenience. To generate your jars, just run the following in your terminal:

```bash
./build.sh
````

## What's provided vs. what you need to build

**Provided:**
- Command-line argument parsing and validation for both programs (bad port,
  bad delay, missing file, wrong number of arguments, all handled).
- Reading the subject data file and decoding it as JSON.
- The client's entire interactive command loop: reading a line, splitting
  it into tokens, checking argument counts, printing usage messages for bad
  input *without* contacting the server, and handling `quit`.
- `ProtocolMessage` / `ProtocolException` / `SimpleJson`, covered below.

**Left for you (marked `TODO` in the skeletons):**
- All socket code: opening connections, reading/writing lines, closing them.
- All thread code: your concurrency model (thread-per-connection,
  thread-per-request, or a worker pool).
- Your locking strategy: per-subject read/write locks, where the artificial
  delay is placed relative to lock acquisition, and the lock ordering that
  makes `TRANSFER` deadlock-free.
- Validating the subject data file's contents against the rules in the
  spec (unique `subjectCode`, positive `capacity`,
  `enrolledStudentIds.length <= capacity`) and choosing your own in-memory
  structure to hold subjects in.
- The server console (`status` / `stop`) and the operational log.
- Persistence: writing to disk after every successful write, and reloading
  from that file (not the original subject data file) on restart.

If you're not sure whether something is "provided" or "yours to build",
search for `TODO` in `SubjectServer.java` and `SubjectClient.java`: that
marks every place you need to add code.

## Using `ProtocolMessage`

This is the class you'll actually use for building your request and response objects to communicate between clients and server. It builds and parses the
exact JSON shapes defined in the Communication Format and Functional
Requirements sections of the assignment specs, so you don't hand-write JSON anywhere.

**Client side: sending a request and reading the reply:**

```java
ProtocolMessage request = ProtocolMessage.enrolRequest("COMP90015", "1234567");
out.write(request.toJson());
out.write("\n");
out.flush();

String line = in.readLine();
ProtocolMessage response = ProtocolMessage.parse(line);

if (response.getStatus().equals(ProtocolMessage.STATUS_SUCCESS)) {
    System.out.println("Enrolled.");
} else {
    System.out.println(response.getStatus() + ": " + response.getString("message"));
}
```

**Server side: handling one incoming request:**

```java
ProtocolMessage response;
try {
    ProtocolMessage request = ProtocolMessage.parse(line);

    switch (request.getOp()) {
        case ProtocolMessage.OP_QUERY: {
            String code = request.requireString("subjectCode");
            // ... look up the subject, under the appropriate lock ...
            response = ProtocolMessage.successResponse(
                ProtocolMessage.queryData(code, subject.getCapacity(),
                    subject.getEnrolledCount(),
                    new ArrayList<>(subject.getEnrolledStudentIds())));
            break;
        }
        case ProtocolMessage.OP_ENROL: {
            String code = request.requireString("subjectCode");
            String studentId = request.requireString("studentId");
            // ... your locking + business logic here ...
            response = ProtocolMessage.successResponse();
            break;
        }
        // ... WITHDRAW, TRANSFER, UPDATE_CAPACITY follow the same pattern ...
        default:
            response = ProtocolMessage.errorResponse(
                ProtocolMessage.STATUS_INVALID_REQUEST, "Unknown op: " + request.getOp());
    }
} catch (ProtocolException e) {
    // Malformed request: respond, don't crash, don't close the connection.
    response = ProtocolMessage.errorResponse(ProtocolMessage.STATUS_INVALID_REQUEST, e.getMessage());
}

out.write(response.toJson());
out.write("\n");
out.flush();
```

Why `ProtocolException` exists: parsing a line can fail two different ways.
Either the JSON itself can be malformed, or the JSON can be valid but missing a
field your request needs (e.g. `ENROL` with no `studentId`). `requireString`
/ `requireInt` throw `ProtocolException` for the second case so that both
failure modes end up in the same `catch` block above, rather than you
needing to catch two unrelated exception types.

## What `ProtocolMessage` deliberately does NOT do

- It does not decide *when* a status like `FULL`, `NOT_ENROLLED`, or
  `DUPLICATE_ENROLMENT` applies; that's your server's business logic.
- It does not validate business rules (e.g. "capacity must be positive").
  `requireInt` only checks that a field is present and is a whole number;
  a request with `"newCapacity": -5` will parse fine; deciding that's
  invalid and returning `INVALID_REQUEST` is your job.
- It is not thread-safe by itself, and doesn't make `Subject` thread-safe
  either. Any concurrent access to shared state is still entirely your
  responsibility to synchronize correctly.

## `subjects-sample.json`

A small starter file with four subjects, one of which (`COMP90038`) is
already at capacity, so you can test `FULL` immediately without needing to
fill a subject up yourself first. Feel free to edit it or write your own;
it just needs to match the schema in the Subject Data File Format section.
