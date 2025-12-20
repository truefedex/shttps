package com.phlox.simpleserver.auth;

import com.phlox.simpleserver.utils.Utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface UserStore {
    @Nullable
    User authenticate(@NonNull String username, @NonNull String password);
    @Nullable User find(@NonNull String identity);
    @Nullable User get(long i);
    long count();
    boolean isIdentityUsed(@NonNull String identity);
    void create(@NonNull User user) throws Exception;
    @Nullable User createEmptyUserWithAvailableIdentity() throws Exception;

    boolean isUserDirUsed(String userDir);

    boolean update(@NonNull User user);
    boolean update(@NonNull String userIdentity, @NonNull String field, @Nullable Object value);
    boolean delete(@NonNull String identity);
    boolean rename(@NonNull User user, @NonNull String newIdentity);

    void deleteAll();

    UserRightsEvaluator provideUserRightsEvaluator();
    User registerNewUser(@NonNull String identity, @NonNull String password) throws Exception;

    void updateUserAtomically(String identity, Updater<User> predicate) throws Exception;

    default String formatNewUserDir(String rootDirPattern, String userIdentity) {
        String rootDir = null;
        if (rootDirPattern != null && !rootDirPattern.isEmpty()) {
            int starIndex = rootDirPattern.indexOf('*');
            if (starIndex == -1) {
                //all users should have the same root dir
                rootDir = rootDirPattern;
            } else {
                // remove all special chars not suitable as folder name
                String fatFreeIdentity = Utils.makeFolderNameCompatible(userIdentity);
                int counter = 0;
                boolean userDirUsed;
                do {
                    rootDir = rootDirPattern.replace("*",
                            (fatFreeIdentity.isEmpty() || counter > 0) ?
                                    fatFreeIdentity + counter : fatFreeIdentity);
                    counter++;
                    userDirUsed = isUserDirUsed(rootDir);
                } while (userDirUsed);
            }
        }
        return rootDir;
    }

    @FunctionalInterface
    interface Updater<T> {
        T process(T t) throws Exception;
    }
}
