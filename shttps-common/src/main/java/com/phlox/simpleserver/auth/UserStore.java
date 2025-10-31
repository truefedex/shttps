package com.phlox.simpleserver.auth;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface UserStore {
    @Nullable
    User authenticate(@NonNull String username, @NonNull String password);
    @Nullable User find(@NonNull String identity);
    @Nullable User get(long i);
    long count();
    boolean isIdentityUsed(@NonNull String identity);
    boolean create(@NonNull User user);
    @Nullable User createEmptyUserWithAvailableIdentity();
    boolean update(@NonNull User user);
    boolean update(@NonNull String userIdentity, @NonNull String field, @Nullable Object value);
    boolean delete(@NonNull String identity);
    boolean rename(@NonNull User user, @NonNull String newIdentity);

    void deleteAll();

    UserRightsEvaluator provideUserRightsEvaluator();
    User registerNewUser(@NonNull String identity, @NonNull String password);
}
