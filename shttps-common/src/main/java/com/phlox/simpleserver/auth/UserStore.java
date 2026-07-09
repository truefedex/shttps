package com.phlox.simpleserver.auth;

import com.phlox.simpleserver.utils.Utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface UserStore {
    @Nullable
    User authenticate(@NotNull String username, @NotNull String password);
    @Nullable User find(@NotNull String identity);
    @Nullable User get(long i);
    long count();
    boolean isIdentityUsed(@NotNull String identity);
    void create(@NotNull User user) throws Exception;
    @Nullable User createEmptyUserWithAvailableIdentity() throws Exception;

    boolean isUserDirUsed(String userDir);

    boolean update(@NotNull User user);
    boolean update(@NotNull String userIdentity, @NotNull String field, @Nullable Object value);
    boolean delete(@NotNull String identity);
    boolean rename(@NotNull User user, @NotNull String newIdentity);

    void deleteAll();

    UserRightsEvaluator provideUserRightsEvaluator();
    User registerNewUser(@NotNull String identity, @NotNull String password) throws Exception;

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
