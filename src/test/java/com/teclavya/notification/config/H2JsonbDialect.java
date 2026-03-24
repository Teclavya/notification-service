package com.teclavya.notification.config;

import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.SqlTypes;

/**
 * H2 dialect that maps JSONB -> clob so tests can use H2 in-memory DB
 * while production code uses PostgreSQL JSONB columns.
 */
public class H2JsonbDialect extends H2Dialect {

    @Override
    protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
        super.registerColumnTypes(typeContributions, serviceRegistry);
    }

    @Override
    public String columnType(int sqlTypeCode) {
        if (sqlTypeCode == SqlTypes.JSON) {
            return "clob";
        }
        return super.columnType(sqlTypeCode);
    }
}