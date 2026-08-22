package com.phlox.simpleserver.handlers.database;

import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.websocket.WebSocketCloseCodes;
import com.phlox.server.websocket.WebSocketRequestHandler;
import com.phlox.server.websocket.WebSocketSession;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.DeadlineDatabaseOperations;
import com.phlox.simpleserver.database.DeadlineExceededException;
import com.phlox.simpleserver.database.TransactionAbortHandle;
import com.phlox.simpleserver.database.TransactionAbortedException;
import com.phlox.simpleserver.database.operations.DBOperationException;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs several database commands inside one transaction, over one WebSocket connection.
 * <p>
 * Everything the client sends on a connection happens in a single transaction, committed only when
 * the client asks for it and rolled back on anything else - a failed command, either timeout, or the
 * connection going away. That is what the ordinary {@code /api/db/*} endpoints can not offer: each
 * of them is its own transaction, so a client doing several steps has no way to undo the earlier
 * ones when a later one fails.
 * <p>
 * The data commands are {@code query} (arbitrary SQL), {@code insert}, {@code update},
 * {@code delete} and {@code select}, plus {@code commit}. Each is a thin adapter over the operation
 * class the matching HTTP endpoint uses - see {@link TransactionCommands} - so the config gates and
 * the rights checks are the same ones rather than a second copy. Reading the schema and reading a
 * single cell stay on HTTP.
 * <p>
 * Two things differ from the HTTP endpoints on purpose. Parameters arrive as real JSON, so
 * {@code filters} and {@code values} are nested objects rather than strings holding JSON. And a
 * reading command always has a row limit: {@code GET /api/db/table} may return a whole table because
 * it streams the answer, but here the rows are read into memory while the write thread is held, so a
 * missing {@code limit} becomes {@value #DEFAULT_LIMIT} and any limit is capped at
 * {@value #MAX_LIMIT}.
 * <p>
 * <h3>Why it is bounded</h3>
 * An open transaction occupies the database's single write thread for its whole life, so while one
 * of these sessions is running every other write - and every read that runs inside a transaction of
 * its own, which since the operations refactor includes {@code /api/db/schema} and
 * {@code /api/db/table} - waits behind it. The two timeouts are therefore a correctness mechanism
 * rather than a courtesy, and only one session is allowed at a time.
 * <p>
 * <h3>The threads involved</h3>
 * <ul>
 *     <li>the <b>connection thread</b> runs the read loop; it parses each frame and queues it,
 *     and must never block, or the server would stop noticing close frames, pings and disconnects</li>
 *     <li>the <b>hosting thread</b> does nothing but sit in {@code runTransaction} and send the one
 *     terminal frame once it returns - it is the only thread that can know whether the commit
 *     itself succeeded, because the commit happens after the transaction scope returns</li>
 *     <li>the <b>database write thread</b> runs the scope: it takes commands off the queue, executes
 *     them, sends their replies, and enforces both deadlines</li>
 * </ul>
 * Sending from two threads is safe - the frame writer serializes whole frames - and the ordering
 * holds because the scope is the only consumer of the command queue and the terminal frame is sent
 * strictly after the scope has finished.
 *
 * <h3>The protocol</h3>
 * <pre>
 * C-&gt;S  {"id":1,"command":"query","sql":"...","limit":100,"offset":0,"includeNames":true}
 * C-&gt;S  {"id":2,"command":"insert","table":"t","values":{"a":1}}
 * C-&gt;S  {"id":3,"command":"update","table":"t","values":{"a":2},"filters":{"clauses":["id="],"args":[7]}}
 * C-&gt;S  {"id":4,"command":"delete","table":"t","filters":{"clauses":["id="],"args":[7]}}
 * C-&gt;S  {"id":5,"command":"select","table":"t","limit":100,"includeTotal":true}
 * C-&gt;S  {"id":6,"command":"commit"}
 * S-&gt;C  {"id":1,"ok":true,"result":{"offset":0,"limit":100,"data":[[1]]}}   //result null for DDL
 * S-&gt;C  {"id":2,"ok":true,"result":{"generated_id":42}}                     //generated_ids for a batch
 * S-&gt;C  {"id":3,"ok":true,"result":{"updated_rows":1}}
 * S-&gt;C  {"id":4,"ok":true,"result":{"deleted_rows":1}}
 * S-&gt;C  {"id":5,"ok":true,"result":{"total":7,"data":[[1,"a"]]}}
 * S-&gt;C  {"id":1,"ok":false,"error":{"kind":"FAILED","message":"..."}}
 * S-&gt;C  {"id":6,"ok":true,"committed":true}
 * </pre>
 */
public class DBTransactionWebSocketHandler extends WebSocketRequestHandler {
    public static final String PATH = "/api/db/transaction";

    private static final SHTTPSLoggerProxy.Logger logger =
            SHTTPSLoggerProxy.getLogger(DBTransactionWebSocketHandler.class);

    /**
     * Close codes, private use range (4000-4999), so they can not be confused with the protocol's.
     * They deliberately echo the HTTP status codes their situations correspond to, and the four
     * that a command can end a session with are the ones {@link #closeCodeFor} maps onto.
     */
    public static final int CLOSE_CODE_BAD_REQUEST = 4400;
    public static final int CLOSE_CODE_FORBIDDEN = 4403;
    public static final int CLOSE_CODE_NOT_FOUND = 4404;
    public static final int CLOSE_CODE_INACTIVITY_TIMEOUT = 4408;
    public static final int CLOSE_CODE_BUSY = 4409;
    public static final int CLOSE_CODE_LIFETIME_EXCEEDED = 4410;
    public static final int CLOSE_CODE_FAILED = 4500;

    /**
     * The close code for a session a command ended, chosen so that it says the same thing as the
     * error frame that went out just before it.
     */
    private static int closeCodeFor(DBOperationException.Kind kind) {
        switch (kind) {
            case BAD_REQUEST:
                return CLOSE_CODE_BAD_REQUEST;
            case FORBIDDEN:
            case DISABLED:
                //"you may not" and "nobody may, it is switched off" are both a refusal to the
                //client, exactly as they are both a 403 over HTTP
                return CLOSE_CODE_FORBIDDEN;
            case NOT_FOUND:
                return CLOSE_CODE_NOT_FOUND;
            case FAILED:
            default:
                return CLOSE_CODE_FAILED;
        }
    }

    private static final int DEFAULT_LIMIT = 100;
    /**
     * A result is read fully into memory while the write thread is held, so the client does not get
     * to decide how much of the database to buffer.
     */
    private static final int MAX_LIMIT = 1000;
    /** Enough for a client that pipelines a little; a client that pipelines a lot is misbehaving. */
    private static final int MAX_PENDING_COMMANDS = 8;

    private static final String ATTRIBUTE_USER = "dbTransactionUser";
    private static final String ATTRIBUTE_SESSION = "dbTransactionSession";

    private final Holder<Database> database;
    private final SHTTPSConfig config;
    private final AuthManager authManager;

    /** The one session allowed at a time, or null. */
    private final AtomicReference<TxSession> current = new AtomicReference<>(null);

    public DBTransactionWebSocketHandler(Holder<Database> database, SHTTPSConfig config,
                                         @NotNull AuthManager authManager) {
        this.database = database;
        this.config = config;
        this.authManager = authManager;
        //a close from another thread does not interrupt a pending read, so the read loop has to come
        //up for air often enough that a rolled-back session's socket does not linger
        options.idlePingIntervalMillis = 1000;
        options.closeHandshakeTimeoutMillis = 1000;
    }

    /**
     * A page on another origin can open a WebSocket in a way it can not open an XHR, so without a
     * check here it could drive a transaction with the user's cookies. The browser is no help: it
     * sends {@code Origin} but performs no preflight and ignores {@code Access-Control-Allow-Origin}
     * for a handshake, so the CORS configuration only means anything on this endpoint if the server
     * enforces it itself - which is what this does.
     * <p>
     * Allowed are: a page served from this very host, a client that sends no {@code Origin} at all
     * (nothing but a browser does), and any origin the configured CORS rules cover. A {@code "*"}
     * rule counts, exactly as it does for ordinary requests, so it opens this endpoint to any page
     * on the internet - with session authentication on, that page would connect carrying the user's
     * cookies.
     * <p>
     * Read per handshake, so editing the rules takes effect without a restart.
     */
    @Override
    protected boolean isOriginAllowed(@NotNull Request request, @Nullable String origin) {
        if (isSameOriginAsHost(request, origin)) {
            return true;
        }
        return CORSMiddleware.findRuleForOrigin(config.getCORSRules(), origin) != null;
    }

    /**
     * Takes the user of the handshake along; the request context they come from is gone by the time
     * the first frame arrives.
     */
    @Override
    protected void onSessionCreated(RequestContext context, WebSocketSession session) {
        //a wrapper, because the attribute map rejects nulls and "no user" is a real, allowed state
        //when authentication is off - an absent attribute must stay distinguishable from it
        session.getAttributes().put(ATTRIBUTE_USER,
                new AuthenticatedUser(authManager.getAuthenticatedUser(context)));
    }

    @Override
    public void onOpen(WebSocketSession session) {
        //refusals close the session and return; throwing here would skip onClose entirely
        AuthenticatedUser authenticated = (AuthenticatedUser) session.getAttributes().get(ATTRIBUTE_USER);
        if (authenticated == null) {
            //onSessionCreated did not run, so nothing is known about who this is
            session.close(CLOSE_CODE_FORBIDDEN, "Not authenticated");
            return;
        }
        if (authenticated.user == null && !config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) {
            session.close(CLOSE_CODE_FORBIDDEN, "Not authenticated");
            return;
        }
        //before anything is claimed and before the transaction starts: a session occupies the
        //database's single write thread from the moment it opens, so who may open one at all is a
        //question that has to be answered here rather than per command
        if (!mayOpenTransaction(authenticated.user)) {
            session.close(CLOSE_CODE_FORBIDDEN, "Not allowed to open a database transaction");
            return;
        }
        //no config gate on the session itself: the commands are gated differently from each other -
        //insert/update/delete on the table editing API, query on the custom SQL API, and select on
        //nothing at all, exactly as their HTTP equivalents are. Gating the handshake on any one of
        //them would refuse sessions that had every right to run, so each command carries its own
        //check and a client asking for a switched-off one is refused on its first command
        //resolved once: the app can swap the database under a live session, and mixing two of them
        //in one transaction would be worse than finishing with the one we started on
        Database db = database.get();
        if (db == null) {
            session.close(CLOSE_CODE_NOT_FOUND, "No database is open");
            return;
        }

        TxSession tx = new TxSession(session, db, authenticated.user);
        //claimed last, so no refusal above has to remember to release it
        if (!current.compareAndSet(null, tx)) {
            session.close(CLOSE_CODE_BUSY, "Another transaction is already in progress");
            return;
        }
        session.getAttributes().put(ATTRIBUTE_SESSION, tx);
        try {
            tx.start();
        } catch (Throwable t) {
            //nothing is going to release the slot if the thread never ran
            logger.stackTrace(t);
            current.compareAndSet(tx, null);
            session.close(WebSocketCloseCodes.INTERNAL_SERVER_ERROR, "Could not start the transaction");
        }
    }

    /**
     * Parses and queues, and does no more than that: this runs on the thread that reads frames.
     */
    @Override
    public void onTextMessage(WebSocketSession session, String message) {
        TxSession tx = (TxSession) session.getAttributes().get(ATTRIBUTE_SESSION);
        if (tx == null) {
            return;//refused in onOpen; the close frame is already on its way
        }
        //a malformed frame becomes a command that fails when its turn comes, so that the answer
        //arrives in order with every other answer
        tx.submit(Command.parse(message, config, authManager));
    }

    @Override
    public void onClose(WebSocketSession session, int code, String reason) {
        TxSession tx = (TxSession) session.getAttributes().get(ATTRIBUTE_SESSION);
        if (tx != null) {
            //wakes the scope wherever it is waiting; the hosting thread does the rest, including
            //releasing the slot, because it is the one that knows the transaction is really over
            tx.abortHandle.abort("client closed the connection");
        }
    }

    /**
     * Whether this user may open a transaction at all, which is a coarser question than whether any
     * particular command is allowed.
     * <p>
     * Resolved through {@link com.phlox.simpleserver.auth.UserRightsEvaluator#userDBRights}, which
     * reads the user's role with an ordinary query rather than a transaction - so asking costs none
     * of the write-thread time this check exists to protect.
     *
     * @param user the authenticated user, or null when authentication is switched off
     */
    private boolean mayOpenTransaction(@Nullable User user) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) {
            //no users to have rights, as everywhere else in the database API
            return true;
        }
        if (user == null) {
            return false;
        }
        try {
            return authManager.getUserRightsEvaluator().userDBRights(user)
                    .contains(User.DBRights.USE_TRANSACTION);
        } catch (Exception e) {
            //reading the rights of a user with a role means a database lookup, which can fail;
            //this is not the place to guess in the user's favour
            logger.stackTrace(e);
            return false;
        }
    }

    /** Wrapper so that "no user because authentication is off" is not the same as "no attribute". */
    private static final class AuthenticatedUser {
        final @Nullable User user;

        AuthenticatedUser(@Nullable User user) {
            this.user = user;
        }
    }

    /** One command from the client, already parsed. */
    private static final class Command {
        static final int KIND_DATA = 0;
        static final int KIND_COMMIT = 1;
        static final int KIND_INVALID = 2;

        final int kind;
        final Object id;
        final TransactionCommands.TransactionCommand payload;
        final String parseError;

        private Command(int kind, Object id, TransactionCommands.TransactionCommand payload,
                        String parseError) {
            this.kind = kind;
            this.id = id;
            this.payload = payload;
            this.parseError = parseError;
        }

        /**
         * Never throws: anything wrong with a frame becomes an invalid command, so that the
         * complaint is delivered in its turn by the thread that sends every other reply rather than
         * out of band from the thread that reads frames.
         */
        static Command parse(String message, SHTTPSConfig config, AuthManager authManager) {
            Object id = JSONObject.NULL;
            try {
                JSONObject json = new JSONObject(message);
                id = json.opt("id") == null ? JSONObject.NULL : json.opt("id");
                if (TransactionCommands.COMMAND_COMMIT.equals(json.optString("command", ""))) {
                    return new Command(KIND_COMMIT, id, null, null);
                }
                return new Command(KIND_DATA, id,
                        TransactionCommands.parse(json, config, authManager, DEFAULT_LIMIT, MAX_LIMIT),
                        null);
            } catch (DBOperationException e) {
                return invalid(id, e.getMessage());
            } catch (Exception e) {
                return invalid(id, "Malformed command: " + e.getMessage());
            }
        }

        private static Command invalid(Object id, String error) {
            return new Command(KIND_INVALID, id, null, error);
        }
    }

    /** One transaction, and everything that belongs to it. */
    private final class TxSession {
        private final WebSocketSession session;
        private final Database db;
        private final @Nullable User user;
        private final BlockingQueue<Command> commands = new ArrayBlockingQueue<>(MAX_PENDING_COMMANDS);
        final TransactionAbortHandle abortHandle = new TransactionAbortHandle();
        private final Thread host;
        /** Set just before the scope returns, so a failure afterwards is known to be the commit. */
        private volatile boolean commitRequested = false;

        TxSession(WebSocketSession session, Database db, @Nullable User user) {
            this.session = session;
            this.db = db;
            this.user = user;
            this.host = new Thread(this::run, "DBTransaction-" + session.getRemoteAddress());
            this.host.setDaemon(true);
        }

        void start() {
            host.start();
        }

        /** Queues a command, or ends the transaction if the client is running too far ahead. */
        void submit(Command command) {
            if (!commands.offer(command)) {
                //offer, never put: blocking here would block the thread that reads frames
                abortHandle.abort("too many commands queued");
            }
        }

        /** The hosting thread: waits for the transaction and has the last word on the wire. */
        private void run() {
            Object commitId = null;
            String failure = null;
            int closeCode = WebSocketCloseCodes.NORMAL_CLOSURE;
            try {
                commitId = db.runTransaction(this::execute, abortHandle);
            } catch (DeadlineExceededException e) {
                failure = e.getMessage();
                closeCode = e.kind == DeadlineExceededException.Kind.INACTIVITY
                        ? CLOSE_CODE_INACTIVITY_TIMEOUT : CLOSE_CODE_LIFETIME_EXCEEDED;
            } catch (TransactionAbortedException e) {
                failure = e.getMessage();
                closeCode = CLOSE_CODE_FAILED;
            } catch (DBOperationException e) {
                //the close code says the same thing the error frame did: a client that only
                //watches the socket learns whether its command was wrong or the database was
                failure = e.getMessage();
                closeCode = closeCodeFor(e.kind);
            } catch (Exception e) {
                //the commit is the only thing that runs after the scope returned
                failure = (commitRequested ? "Commit failed: " : "Transaction failed: ") + e.getMessage();
                closeCode = CLOSE_CODE_FAILED;
                logger.d("Transaction for " + session.getRemoteAddress() + " failed: " + e);
            } finally {
                try {
                    if (failure == null) {
                        //only reachable when runTransaction returned, i.e. the commit really happened
                        send(new JSONObject().put("id", commitId == null ? JSONObject.NULL : commitId)
                                .put("ok", true).put("committed", true));
                        session.close(WebSocketCloseCodes.NORMAL_CLOSURE, "committed");
                    } else {
                        session.close(closeCode, failure == null ? "" : failure);
                    }
                } catch (Throwable t) {
                    logger.d("Could not report the transaction outcome: " + t);
                } finally {
                    //last of all, and only ours to release: a second session may start now
                    current.compareAndSet(TxSession.this, null);
                }
            }
        }

        /**
         * The transaction itself, on the database's write thread. Returns the id of the commit
         * command, which is the only thing that has to reach the hosting thread; anything thrown
         * from here rolls the transaction back.
         */
        private Object execute(DatabaseOperations ops) throws Exception {
            int inactivityMillis = config.getDBTransactionInactivityTimeoutMillis();
            //started here rather than at onOpen: a session that queued behind another writer would
            //otherwise be born with none of its budget left
            long deadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(config.getDBTransactionMaxLifetimeMillis());
            //bounds the work as well as the waiting - a batch of statements never consults the clock
            DatabaseOperations deadlineOps = new DeadlineDatabaseOperations(ops, deadlineNanos);

            while (true) {
                long leftNanos = deadlineNanos - System.nanoTime();
                if (leftNanos <= 0) {
                    throw DeadlineExceededException.lifetime();
                }
                long waitMillis = Math.min(inactivityMillis, TimeUnit.NANOSECONDS.toMillis(leftNanos) + 1);
                Command command = commands.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (command == null) {
                    throw System.nanoTime() - deadlineNanos >= 0
                            ? DeadlineExceededException.lifetime()
                            : DeadlineExceededException.inactivity();
                }
                if (command.kind == Command.KIND_COMMIT) {
                    commitRequested = true;
                    return command.id;
                }
                runCommand(deadlineOps, command);
            }
        }

        /**
         * Runs one command and answers it. A refusal is reported and then rethrown: a transaction
         * that carried on after a failed command would commit the half of the work that did land.
         */
        private void runCommand(DatabaseOperations ops, Command command) throws Exception {
            if (command.kind == Command.KIND_INVALID) {
                sendError(command.id, DBOperationException.Kind.BAD_REQUEST, command.parseError);
                throw DBOperationException.badRequest(command.parseError);
            }
            JSONObject result;
            try {
                //every command materializes inside the transaction: the next one needs the cursor
                //finished, and on Android a cursor can not outlive the transaction it was made in
                result = command.payload.execute(ops, user);
            } catch (DBOperationException e) {
                sendError(command.id, e.kind, e.getMessage());
                throw e;
            }
            send(new JSONObject()
                    .put("id", command.id)
                    .put("ok", true)
                    .put("result", result == null ? JSONObject.NULL : result));
        }

        private void sendError(Object id, DBOperationException.Kind kind, String message) {
            try {
                send(new JSONObject()
                        .put("id", id)
                        .put("ok", false)
                        .put("error", new JSONObject()
                                .put("kind", kind.name())
                                .put("message", message == null ? "" : message)));
            } catch (IOException e) {
                logger.d("Could not report a command failure: " + e.getMessage());
            }
        }

        private void send(JSONObject frame) throws IOException {
            session.sendText(frame.toString());
        }
    }
}
