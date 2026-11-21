package com.phlox.simpleserver.handlers.files.webdav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.StorageQuota;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebDavOverwriteTest {

    @Test
    public void aCopyThatDoesNotFitLeavesTheDestination(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("src.txt"), "from");
        Files.writeString(temp.resolve("keep.txt"), "keep");
        DocumentFile root = DocumentFile.fromFile(temp.toFile());
        User user = new User("u", "h");
        user.storageLimit = 0L;

        Request request = new Request();
        request.path = "/src.txt";
        Response response = helper(user, true).handleCopy(request, root.findFile("src.txt"), root,
                new StorageQuota(user, store(user)),
                new WebDavCopyMoveHelper.DestInfo(root.findFile("keep.txt"), root, "/keep.txt", "keep.txt", true));

        assertEquals(507, response.code);
        assertEquals("keep", Files.readString(temp.resolve("keep.txt")));
        assertEquals("from", Files.readString(temp.resolve("src.txt")));
    }

    @Test
    public void aSuccessfulCopyReplacesTheDestinationAfterwards(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("src.txt"), "from");
        Files.writeString(temp.resolve("keep.txt"), "keep");
        DocumentFile root = DocumentFile.fromFile(temp.toFile());

        Request request = new Request();
        request.path = "/src.txt";
        Response response = helper(null, true).handleCopy(request, root.findFile("src.txt"), root,
                new StorageQuota(null, store(null)),
                new WebDavCopyMoveHelper.DestInfo(root.findFile("keep.txt"), root, "/keep.txt", "keep.txt", true));

        assertEquals(204, response.code);
        assertEquals("from", Files.readString(temp.resolve("keep.txt")));
        assertEquals("from", Files.readString(temp.resolve("src.txt")));
        for (String name : temp.toFile().list()) {
            assertFalse(name.contains(".shttps-"), name);
        }
    }

    @Test
    public void withoutAtomicUploadsTheDestinationIsReplacedInPlace(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("src.txt"), "from");
        Files.writeString(temp.resolve("keep.txt"), "keep");
        DocumentFile root = DocumentFile.fromFile(temp.toFile());

        Request request = new Request();
        request.path = "/src.txt";
        Response response = helper(null, false).handleCopy(request, root.findFile("src.txt"), root,
                new StorageQuota(null, store(null)),
                new WebDavCopyMoveHelper.DestInfo(root.findFile("keep.txt"), root, "/keep.txt", "keep.txt", true));

        assertEquals(204, response.code);
        assertEquals("from", Files.readString(temp.resolve("keep.txt")));
        assertEquals(2, temp.toFile().list().length);
    }

    @Test
    public void withoutAtomicUploadsTheSpaceOfTheReplacedFileCounts(@TempDir Path temp) throws Exception {
        //the quota is exactly full with keep.txt: replacing it with a file of the same size fits,
        //because the old one is gone first. Staged, it would not
        Files.writeString(temp.resolve("src.txt"), "from");
        Files.writeString(temp.resolve("keep.txt"), "keep");
        DocumentFile root = DocumentFile.fromFile(temp.toFile());
        User user = new User("u", "h");
        user.storageLimit = 8L;
        user.usedStorage = 8L;

        Request request = new Request();
        request.path = "/src.txt";
        Response response = helper(user, false).handleCopy(request, root.findFile("src.txt"), root,
                new StorageQuota(user, store(user)),
                new WebDavCopyMoveHelper.DestInfo(root.findFile("keep.txt"), root, "/keep.txt", "keep.txt", true));

        assertEquals(204, response.code);
        assertEquals("from", Files.readString(temp.resolve("keep.txt")));
    }

    private static WebDavCopyMoveHelper helper(User user, boolean atomic) {
        return new WebDavCopyMoveHelper(null, store(user), new LockManager(), atomic);
    }

    private static UserStore store(User user) {
        return (UserStore) Proxy.newProxyInstance(UserStore.class.getClassLoader(),
                new Class<?>[]{UserStore.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "store";
                        if ("hashCode".equals(method.getName())) return 1;
                        return proxy == args[0];
                    }
                    if ("updateUserAtomically".equals(method.getName()) && user != null) {
                        ((UserStore.Updater<User>) args[1]).process(user);
                        return null;
                    }
                    if ("provideUserRightsEvaluator".equals(method.getName())) {
                        return new UserRightsEvaluator(new Holder<>(null));
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }
}
