package kotliquery

import kotliquery.LoanPattern.using
import kotliquery.action.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.InputStream
import java.math.BigDecimal
import java.net.URL
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.time.*


/**
 * Database Session.
 */
open class Session(
    open val connection: Connection,
    open val returnGeneratedKeys: Boolean = true,
    open val autoGeneratedKeys: List<String> = listOf(),
    var transactional: Boolean = false,
    open val strict: Boolean = false,
    open val queryTimeout: Int? = null
) : Closeable {

    override fun close() {
        transactional = false
        connection.close()
    }

    private val logger = LoggerFactory.getLogger(Session::class.java)

    private inline fun <reified T> PreparedStatement.setTypedParam(idx: Int, param: Parameter<T>) {
        if (param.value == null) {
            this.setNull(idx, param.sqlType())
        } else {
            setParam(idx, param.value)
        }
    }

    private fun PreparedStatement.setParam(idx: Int, v: Any?) {
        if (v == null) {
            this.setObject(idx, null)
        } else {
            when (v) {
                is String -> this.setString(idx, v)
                is Byte -> this.setByte(idx, v)
                is Boolean -> this.setBoolean(idx, v)
                is Int -> this.setInt(idx, v)
                is Long -> this.setLong(idx, v)
                is Short -> this.setShort(idx, v)
                is Double -> this.setDouble(idx, v)
                is Float -> this.setFloat(idx, v)
                is ZonedDateTime -> this.setTimestamp(idx, java.sql.Timestamp.from(v.toInstant()))
                is OffsetDateTime -> this.setTimestamp(idx, java.sql.Timestamp.from(v.toInstant()))
                is Instant -> this.setTimestamp(idx, java.sql.Timestamp.from(v))
                is LocalDateTime -> this.setTimestamp(idx, java.sql.Timestamp.valueOf(v))
                is LocalDate -> this.setDate(idx, java.sql.Date.valueOf(v))
                is LocalTime -> this.setTime(idx, java.sql.Time.valueOf(v))
                is org.joda.time.DateTime -> this.setTimestamp(idx, java.sql.Timestamp(v.millis))
                is org.joda.time.LocalDateTime -> this.setTimestamp(idx, java.sql.Timestamp(v.toDateTime().millis))
                is org.joda.time.LocalDate -> this.setDate(idx, java.sql.Date(v.toDateTimeAtStartOfDay().millis))
                is org.joda.time.LocalTime -> this.setTime(idx, java.sql.Time(v.toDateTimeToday().millis))
                is java.sql.Timestamp -> this.setTimestamp(idx, v) // extends java.util.Date
                is java.sql.Time -> this.setTime(idx, v)
                is java.sql.Date -> this.setDate(idx, v)
                is java.sql.SQLXML -> this.setSQLXML(idx, v)
                is java.util.Date -> this.setTimestamp(idx, java.sql.Timestamp(v.time))
                is ByteArray -> this.setBytes(idx, v)
                is InputStream -> this.setBinaryStream(idx, v)
                is BigDecimal -> this.setBigDecimal(idx, v)
                is java.sql.Array -> this.setArray(idx, v)
                is URL -> this.setURL(idx, v)
                // java.util.UUID should be set via setObject
                else -> this.setObject(idx, v)
            }
        }
    }

    fun createArrayOf(typeName: String, items: Collection<Any>): java.sql.Array =
        connection.underlying.createArrayOf(typeName, items.toTypedArray())

    fun populateParams(query: Query, stmt: PreparedStatement): PreparedStatement {
        if (query.replacementMap.isNotEmpty()) {
            query.replacementMap.forEach { paramName, occurrences ->
                occurrences.forEach {
                    stmt.setTypedParam(it + 1, query.paramMap[paramName].param())
                }
            }
        } else {
            query.params.forEachIndexed { index, value ->
                stmt.setTypedParam(index + 1, value.param())
            }
        }

        return stmt
    }

    fun createPreparedStatement(query: Query): PreparedStatement {
        val stmt = if (returnGeneratedKeys) {
            if (connection.driverName == "oracle.jdbc.driver.OracleDriver") {
                connection.underlying.prepareStatement(query.cleanStatement, autoGeneratedKeys.toTypedArray())
            } else {
                connection.underlying.prepareStatement(query.cleanStatement, Statement.RETURN_GENERATED_KEYS)
            }
        } else {
            connection.underlying.prepareStatement(query.cleanStatement)
        }

        if (queryTimeout != null) {
            stmt.queryTimeout = queryTimeout!!
        }

        return populateParams(query, stmt)
    }

    private fun <A> rows(query: Query, extractor: (Row) -> A?): List<A> {
        return using(createPreparedStatement(query)) { stmt ->
            using(stmt.executeQuery()) { rs ->
                val rows = Row(rs).map { row -> extractor.invoke(row) }
                rows.filter { r -> r != null }.map { r -> r!! }.toList()
            }
        }
    }

    private fun rowsBatched(
        statement: String,
        params: Collection<Collection<Any?>>,
        namedParams: Collection<Map<String, Any?>>
    ): List<Int> {
        return using(connection.underlying.prepareStatement(Query(statement).cleanStatement)) { stmt ->
            if (queryTimeout != null) {
                stmt.queryTimeout = queryTimeout!!
            }
            batchUpdates(namedParams, statement, stmt, params)
            stmt.executeBatch().toList()
        }
    }

    private fun rowsBatchedReturningGeneratedKeys(
        statement: String,
        params: Collection<Collection<Any?>>,
        namedParams: Collection<Map<String, Any?>>
    ): List<Long> {
        return using(
            connection.underlying.prepareStatement(
                Query(statement).cleanStatement,
                Statement.RETURN_GENERATED_KEYS
            )
        ) { stmt ->
            batchUpdates(namedParams, statement, stmt, params)
            if (queryTimeout != null) {
                stmt.queryTimeout = queryTimeout!!
            }
            stmt.executeBatch()
            val generatedKeysRs = stmt.generatedKeys
            val keys = mutableListOf<Long>()
            while (generatedKeysRs.next()) {
                keys.add(generatedKeysRs.getLong(1))
            }
            if (keys.isEmpty()) {
                logger.warn("Unexpectedly, Statement#getGeneratedKeys doesn't have any elements for $statement")
            }
            keys.toList()
        }
    }

    private fun batchUpdates(
        namedParams: Collection<Map<String, Any?>>,
        statement: String,
        stmt: PreparedStatement,
        params: Collection<Collection<Any?>>
    ) {
        if (namedParams.isNotEmpty()) {
            val extracted = Query.extractNamedParamsIndexed(statement)
            namedParams.forEach { paramRow ->
                extracted.forEach { paramName, occurrences ->
                    occurrences.forEach {
                        stmt.setTypedParam(it + 1, paramRow[paramName].param())
                    }
                }
                stmt.addBatch()
            }
        } else {
            params.forEach { paramsRow ->
                paramsRow.forEachIndexed { idx, value ->
                    stmt.setTypedParam(idx + 1, value.param())
                }
                stmt.addBatch()
            }
        }
    }


    private fun warningForTransactionMode() {
        if (transactional) {
            logger.warn("Use TransactionalSession instead. The `tx` of `session.transaction { tx -> ... }`")
        }
    }

    fun <A> single(query: Query, extractor: (Row) -> A?): A? {
        warningForTransactionMode()
        val rs = rows(query, extractor)

        return if (strict) {
            when (rs.size) {
                1 -> rs.first()
                0 -> null
                else -> throw SQLException("Expected 1 row but received ${rs.size}.")
            }
        } else {
            if (rs.isNotEmpty()) rs.first() else null
        }
    }

    fun <A> list(query: Query, extractor: (Row) -> A?): List<A> {
        warningForTransactionMode()
        return rows(query, extractor).toList()
    }

    fun batchPreparedNamedStatement(statement: String, params: Collection<Map<String, Any?>>): List<Int> {
        warningForTransactionMode()
        return rowsBatched(statement, emptyList(), params)
    }

    fun batchPreparedNamedStatementAndReturnGeneratedKeys(
        statement: String,
        params: Collection<Map<String, Any?>>
    ): List<Long> {
        warningForTransactionMode()
        return rowsBatchedReturningGeneratedKeys(statement, emptyList(), params)
    }

    fun batchPreparedStatement(statement: String, params: Collection<Collection<Any?>>): List<Int> {
        warningForTransactionMode()
        return rowsBatched(statement, params, emptyList())
    }

    fun batchPreparedStatementAndReturnGeneratedKeys(
        statement: String,
        params: Collection<Collection<Any?>>
    ): List<Long> {
        warningForTransactionMode()
        return rowsBatchedReturningGeneratedKeys(statement, params, emptyList())
    }

    fun forEach(query: Query, operator: (Row) -> Unit) {
        warningForTransactionMode()
        using(createPreparedStatement(query)) { stmt ->
            using(stmt.executeQuery()) { rs ->
                Row(rs).forEach { row -> operator.invoke(row) }
            }
        }
    }

    fun execute(query: Query): Boolean {
        warningForTransactionMode()
        return using(createPreparedStatement(query)) { stmt ->
            stmt.execute()
        }
    }

    fun update(query: Query): Int {
        warningForTransactionMode()
        return using(createPreparedStatement(query)) { stmt ->
            stmt.executeUpdate()
        }
    }

    fun updateAndReturnGeneratedKey(query: Query): Long? {
        warningForTransactionMode()
        return using(createPreparedStatement(query)) { stmt ->
            if (stmt.executeUpdate() > 0) {
                val rs = stmt.generatedKeys
                val hasNext = rs.next()
                if (!hasNext) {
                    logger.warn("Unexpectedly, Statement#getGeneratedKeys doesn't have any elements for " + query.statement)
                }
                rs.getLong(1)
            } else null
        }
    }

    fun run(action: ExecuteQueryAction): Boolean {
        return action.runWithSession(this)
    }

    fun run(action: UpdateQueryAction): Int {
        return action.runWithSession(this)
    }

    fun run(action: UpdateAndReturnGeneratedKeyQueryAction): Long? {
        return action.runWithSession(this)
    }

    fun <A> run(action: ListResultQueryAction<A>): List<A> {
        return action.runWithSession(this)
    }

    fun <A> run(action: NullableResultQueryAction<A>): A? {
        return action.runWithSession(this)
    }

    fun <A> transaction(operation: (TransactionalSession) -> A): A {
        try {
            connection.begin()
            transactional = true
            val tx = TransactionalSession(connection, returnGeneratedKeys, autoGeneratedKeys, strict)
            val result: A = operation.invoke(tx)
            connection.commit()
            return result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            transactional = false
        }
    }

}
