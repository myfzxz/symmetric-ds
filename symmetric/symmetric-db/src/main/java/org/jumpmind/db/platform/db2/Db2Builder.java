package org.jumpmind.db.platform.db2;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.sql.Types;
import java.util.Iterator;
import java.util.List;

import org.jumpmind.db.IDatabasePlatform;
import org.jumpmind.db.alter.AddColumnChange;
import org.jumpmind.db.alter.AddPrimaryKeyChange;
import org.jumpmind.db.alter.PrimaryKeyChange;
import org.jumpmind.db.alter.RemoveColumnChange;
import org.jumpmind.db.alter.RemovePrimaryKeyChange;
import org.jumpmind.db.alter.TableChange;
import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Database;
import org.jumpmind.db.model.Index;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.model.TypeMap;
import org.jumpmind.db.platform.SqlBuilder;
import org.jumpmind.db.util.Jdbc3Utils;
import org.jumpmind.util.Log;

/*
 * The DDL Builder for DB2.
 */
public class Db2Builder extends SqlBuilder {
    
    public Db2Builder(Log log, IDatabasePlatform platform) {
        super(log, platform);
        addEscapedCharSequence("'", "''");
    }

    @Override
    protected String getNativeDefaultValue(Column column) {
        if ((column.getTypeCode() == Types.BIT)
                || (Jdbc3Utils.supportsJava14JdbcTypes() && (column.getTypeCode() == Jdbc3Utils
                        .determineBooleanTypeCode()))) {
            return getDefaultValueHelper().convert(column.getDefaultValue(), column.getTypeCode(),
                    Types.SMALLINT).toString();
        } else {
            return super.getNativeDefaultValue(column);
        }
    }

    @Override
    protected void writeColumnAutoIncrementStmt(Table table, Column column, StringBuilder ddl)  {
        ddl.append("GENERATED BY DEFAULT AS IDENTITY");
    }

    @Override
    public String getSelectLastIdentityValues(Table table) {
        return "VALUES IDENTITY_VAL_LOCAL()";
    }

    @Override
    public void writeExternalIndexDropStmt(Table table, Index index, StringBuilder ddl)  {
        // Index names in DB2 are unique to a schema and hence Derby does not
        // use the ON <tablename> clause
        ddl.append("DROP INDEX ");
        printIdentifier(getIndexName(index), ddl);
        printEndOfStatement(ddl);
    }

    @Override
    protected void writeCastExpression(Column sourceColumn, Column targetColumn, StringBuilder ddl)  {
        String sourceNativeType = getBareNativeType(sourceColumn);
        String targetNativeType = getBareNativeType(targetColumn);

        if (sourceNativeType.equals(targetNativeType)) {
            printIdentifier(getColumnName(sourceColumn), ddl);
        } else {
            String type = getSqlType(targetColumn);

            // DB2 has the limitation that it cannot convert numeric values
            // to VARCHAR, though it can convert them to CHAR
            if (TypeMap.isNumericType(sourceColumn.getTypeCode())
                    && "VARCHAR".equalsIgnoreCase(targetNativeType)) {
                Object sizeSpec = targetColumn.getSize();

                if (sizeSpec == null) {
                    sizeSpec = platform.getPlatformInfo()
                            .getDefaultSize(targetColumn.getTypeCode());
                }
                type = "CHAR(" + sizeSpec.toString() + ")";
            }

            ddl.append("CAST(");
            printIdentifier(getColumnName(sourceColumn), ddl);
            ddl.append(" AS ");
            ddl.append(type);
            ddl.append(")");
        }
    }

    @Override
    protected void processTableStructureChanges(Database currentModel, Database desiredModel,
            Table sourceTable, Table targetTable, List<TableChange> changes, StringBuilder ddl)  {
        // DB2 provides only limited ways to alter a column, so we don't use
        // them
        for (Iterator<TableChange> changeIt = changes.iterator(); changeIt.hasNext();) {
            TableChange change = changeIt.next();

            if (change instanceof AddColumnChange) {
                AddColumnChange addColumnChange = (AddColumnChange) change;

                // DB2 can only add not insert columns
                // Also, DB2 does not allow the GENERATED BY DEFAULT AS IDENTITY
                // clause in
                // the ALTER TABLE ADD COLUMN statement, so we have to rebuild
                // the table instead
                if ((addColumnChange.getNextColumn() == null)
                        && !addColumnChange.getNewColumn().isAutoIncrement()) {
                    processChange(currentModel, desiredModel, addColumnChange, ddl);
                    changeIt.remove();
                }
            }
        }

        for (Iterator<TableChange> changeIt = changes.iterator(); changeIt.hasNext();) {
            TableChange change = changeIt.next();

            if (change instanceof AddPrimaryKeyChange) {
                processChange(currentModel, desiredModel, (AddPrimaryKeyChange) change, ddl);
                changeIt.remove();
            } else if (change instanceof PrimaryKeyChange) {
                processChange(currentModel, desiredModel, (PrimaryKeyChange) change, ddl);
                changeIt.remove();
            } else if (change instanceof RemovePrimaryKeyChange) {
                processChange(currentModel, desiredModel, (RemovePrimaryKeyChange) change, ddl);
                changeIt.remove();
            }
        }
    }

    /*
     * Processes the addition of a column to a table.
     */
    protected void processChange(Database currentModel, Database desiredModel,
            AddColumnChange change, StringBuilder ddl)  {
        ddl.append("ALTER TABLE ");
        printlnIdentifier(getTableName(change.getChangedTable()), ddl);
        printIndent(ddl);
        ddl.append("ADD COLUMN ");
        writeColumn(change.getChangedTable(), change.getNewColumn(), ddl);
        printEndOfStatement(ddl);
        change.apply(currentModel, platform.isDelimitedIdentifierModeOn());
    }

    /*
     * Processes the removal of a column from a table.
     */
    protected void processChange(Database currentModel, Database desiredModel,
            RemoveColumnChange change, StringBuilder ddl)  {
        ddl.append("ALTER TABLE ");
        printlnIdentifier(getTableName(change.getChangedTable()), ddl);
        printIndent(ddl);
        ddl.append("DROP COLUMN ");
        printIdentifier(getColumnName(change.getColumn()), ddl);
        printEndOfStatement(ddl);
        change.apply(currentModel, platform.isDelimitedIdentifierModeOn());
    }

    /*
     * Processes the removal of a primary key from a table.
     */
    protected void processChange(Database currentModel, Database desiredModel,
            RemovePrimaryKeyChange change, StringBuilder ddl)  {
        ddl.append("ALTER TABLE ");
        printlnIdentifier(getTableName(change.getChangedTable()), ddl);
        printIndent(ddl);
        ddl.append("DROP PRIMARY KEY");
        printEndOfStatement(ddl);
        change.apply(currentModel, platform.isDelimitedIdentifierModeOn());
    }

    /*
     * Processes the change of the primary key of a table.
     */
    protected void processChange(Database currentModel, Database desiredModel,
            PrimaryKeyChange change, StringBuilder ddl)  {
        ddl.append("ALTER TABLE ");
        printlnIdentifier(getTableName(change.getChangedTable()), ddl);
        printIndent(ddl);
        ddl.append("DROP PRIMARY KEY");
        printEndOfStatement(ddl);
        writeExternalPrimaryKeysCreateStmt(change.getChangedTable(),
                change.getNewPrimaryKeyColumns(), ddl);
        change.apply(currentModel, platform.isDelimitedIdentifierModeOn());
    }
    
}
