/**
 * The one way the database pages run a statement. Talking to the server is api()'s job, in
 * common.js; this only knows the query endpoint's own parameters.
 */
function runSql(sql, limit = 100) {
    return api('POST', '/api/db/query?' + new URLSearchParams({ 'includeNames': true, 'limit': limit }), sql);
}
