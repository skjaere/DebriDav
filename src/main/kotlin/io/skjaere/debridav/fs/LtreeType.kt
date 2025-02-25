package io.skjaere.debridav.fs

import org.hibernate.HibernateException
import org.hibernate.dialect.Dialect
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.type.descriptor.converter.spi.BasicValueConverter
import org.hibernate.type.descriptor.jdbc.JdbcType
import org.hibernate.type.spi.TypeConfiguration
import org.hibernate.usertype.UserType
import java.io.Serializable
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

class LtreeType : UserType<String?> {
    override fun getSqlType(): Int {
        return Types.OTHER
    }

    override fun returnedClass(): Class<String?>? {
        return String::class.java as Class<String?>?
    }

    override fun equals(p0: String?, p1: String?): Boolean {
        return p0 != null && p1 != null && p0 == p1
    }

    override fun hashCode(p0: String?): Int {
        return p0.hashCode()
    }

    @Throws(HibernateException::class, SQLException::class)
    override fun nullSafeGet(
        resultSet: ResultSet,
        i: Int,
        sharedSessionContractImplementor: SharedSessionContractImplementor?,
        o: Any?
    ): String? {
        return resultSet.getString(i)
    }

    @Throws(SQLException::class)
    override fun nullSafeSet(
        preparedStatement: PreparedStatement,
        s: String?,
        i: Int,
        sharedSessionContractImplementor: SharedSessionContractImplementor?
    ) {
        preparedStatement.setObject(i, s, Types.OTHER)
    }

    @Throws(HibernateException::class)
    override fun deepCopy(s: String?): String? {
        if (s == null) return null
        check(s is String) { "Expected String, but got: " + s.javaClass }
        return s
    }

    override fun isMutable(): Boolean {
        return false
    }

    @Throws(HibernateException::class)
    override fun disassemble(s: String?): Serializable? {
        return s as Serializable?
    }

    @Throws(HibernateException::class)
    override fun assemble(serializable: Serializable, o: Any?): String {
        return serializable.toString()
    }

    override fun replace(s: String?, j1: String?, o: Any?): String? {
        return deepCopy(s)
    }

    override fun getDefaultSqlLength(dialect: Dialect?, jdbcType: JdbcType?): Long {
        return super.getDefaultSqlLength(dialect, jdbcType)
    }

    override fun getDefaultSqlPrecision(dialect: Dialect?, jdbcType: JdbcType?): Int {
        return super.getDefaultSqlPrecision(dialect, jdbcType)
    }

    override fun getDefaultSqlScale(dialect: Dialect?, jdbcType: JdbcType?): Int {
        return super.getDefaultSqlScale(dialect, jdbcType)
    }

    override fun getJdbcType(typeConfiguration: TypeConfiguration?): JdbcType? {
        return super.getJdbcType(typeConfiguration)
    }

    override fun getValueConverter(): BasicValueConverter<String?, Any?>? {
        return super.getValueConverter()
    }
}
