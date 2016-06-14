/*
 *     This file is part of ToroDB.
 *
 *     ToroDB is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ToroDB is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with ToroDB. If not, see <http://www.gnu.org/licenses/>.
 *
 *     Copyright (c) 2014, 8Kdata Technology
 *     
 */

package com.torodb.backend.derby;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jooq.DSLContext;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Meta;
import org.jooq.Result;
import org.jooq.Schema;
import org.jooq.Table;

import com.torodb.backend.DatabaseInterface;
import com.torodb.backend.exceptions.InvalidDatabaseException;
import com.torodb.backend.exceptions.InvalidDatabaseSchemaException;
import com.torodb.backend.meta.TorodbMeta;
import com.torodb.backend.meta.TorodbSchema;
import com.torodb.backend.tables.IdentifierHelper;
import com.torodb.backend.tables.MetaCollectionTable;
import com.torodb.backend.tables.MetaDatabaseTable;
import com.torodb.backend.tables.MetaDocPartTable;
import com.torodb.backend.tables.MetaFieldTable;
import com.torodb.backend.tables.records.MetaCollectionRecord;
import com.torodb.backend.tables.records.MetaDatabaseRecord;
import com.torodb.backend.tables.records.MetaDocPartRecord;
import com.torodb.backend.tables.records.MetaFieldRecord;
import com.torodb.core.TableRef;
import com.torodb.core.TableRefFactory;
import com.torodb.core.transaction.metainf.ImmutableMetaCollection;
import com.torodb.core.transaction.metainf.ImmutableMetaDatabase;
import com.torodb.core.transaction.metainf.ImmutableMetaDocPart;
import com.torodb.core.transaction.metainf.ImmutableMetaField;
import com.torodb.core.transaction.metainf.ImmutableMetaSnapshot;
import com.torodb.core.transaction.metainf.MetaSnapshot;

/**
 *
 */
public class DerbyTorodbMeta implements TorodbMeta {

    private final DatabaseInterface databaseInterface;
    private final ImmutableMetaSnapshot metaSnapshot;
    private final Map<String,Map<String,Map<TableRef,Integer>>> lastIds;

    DerbyTorodbMeta(
            DSLContext dsl,
            TableRefFactory tableRefFactory,
            DatabaseInterface databaseInterface)
    throws SQLException, IOException, InvalidDatabaseException {
        this.databaseInterface = databaseInterface;

        Meta jooqMeta = dsl.meta();
        Connection conn = dsl.configuration().connectionProvider().acquire();

        TorodbSchema.TORODB.checkOrCreate(dsl, jooqMeta, databaseInterface);
        metaSnapshot = loadMetaSnapshot(dsl, jooqMeta, tableRefFactory);
        lastIds = loadRowIds(dsl, metaSnapshot);
        
        dsl.configuration().connectionProvider().release(conn);
    }
    
    public ImmutableMetaSnapshot getCurrentMetaSnapshot() {
        return metaSnapshot;
    }
    
    public Map<String,Map<String,Map<TableRef,Integer>>> getLastIds() {
    	return lastIds;
    }
    
    private ImmutableMetaSnapshot loadMetaSnapshot(
            DSLContext dsl,
            Meta jooqMeta,
            TableRefFactory tableRefFactory) throws InvalidDatabaseSchemaException {
        
        MetaDatabaseTable<MetaDatabaseRecord> metaDatabaseTable = databaseInterface.getMetaDatabaseTable();
        Result<MetaDatabaseRecord> records
                = dsl.selectFrom(metaDatabaseTable)
                    .fetch();
        
        ImmutableMetaSnapshot.Builder metaSnapshotBuilder = new ImmutableMetaSnapshot.Builder();
        for (MetaDatabaseRecord databaseRecord : records) {
            ImmutableMetaDatabase.Builder metaDatabaseBuilder = new ImmutableMetaDatabase.Builder(
                    databaseRecord.getName(), databaseRecord.getIdentifier());
            
            String database = databaseRecord.getName();
            String schemaName = databaseRecord.getIdentifier();
            Schema standardSchema = null;
            for (Schema schema : jooqMeta.getSchemas()) {
                if (schema.getName().equals(schemaName)) {
                    standardSchema = schema;
                    break;
                }
            }
            if (standardSchema == null) {
                throw new IllegalStateException(
                        "The database "+database+" is associated with schema "
                        + schemaName+" but there is no schema with that name");
            }

            checkDatabaseSchema(standardSchema);
            
            MetaCollectionTable<MetaCollectionRecord> collectionTable = databaseInterface.getMetaCollectionTable();
            MetaDocPartTable<Object, MetaDocPartRecord<Object>> docPartTable = databaseInterface.getMetaDocPartTable();
            MetaFieldTable<Object, MetaFieldRecord<Object>> fieldTable = databaseInterface.getMetaFieldTable();
            Result<MetaCollectionRecord> collections = dsl
                    .selectFrom(collectionTable)
                    .where(collectionTable.DATABASE.eq(database))
                    .fetch();
            
            Iterable<? extends Table<?>> existingTables = standardSchema.getTables();
            for (MetaCollectionRecord collection : collections) {
                List<MetaDocPartRecord<Object>> docParts = dsl
                        .selectFrom(docPartTable)
                        .where(docPartTable.DATABASE.eq(database)
                            .and(docPartTable.COLLECTION.eq(collection.getName())))
                        .fetch();
                ImmutableMetaCollection.Builder metaCollectionBuilder = 
                        new ImmutableMetaCollection.Builder(
                                collection.getName(), 
                                collection.getIdentifier());
                
                for (MetaDocPartRecord<Object> docPart : docParts) {
                    if (!docPart.getCollection().equals(collection.getName())) {
                        continue;
                    }
                    
                    TableRef tableRef = docPart.getTableRefValue(tableRefFactory);
                    ImmutableMetaDocPart.Builder metaDocPartBuilder = new ImmutableMetaDocPart.Builder(
                            tableRef, 
                            docPart.getIdentifier());
                    if (!existsTable(docPart.getIdentifier(), existingTables)) {
                        throw new InvalidDatabaseSchemaException(schemaName, "Doc part "+tableRef
                                +" in database "+database
                                +" is associated with table "+docPart.getIdentifier()
                                +" but there is no table with that name in schema "+schemaName);
                    }
                    List<MetaFieldRecord<Object>> fields = dsl
                            .selectFrom(fieldTable)
                            .where(fieldTable.DATABASE.eq(database)
                                .and(fieldTable.COLLECTION.eq(collection.getName()))
                                .and(fieldTable.TABLE_REF.eq(docPart.getTableRef())))
                            .fetch();
                    for (MetaFieldRecord<?> field : fields) {
                        TableRef fieldTableRef = field.getTableRefValue(tableRefFactory);
                        if (!tableRef.equals(fieldTableRef)) {
                            continue;
                        }
                        
                        ImmutableMetaField metaField = new ImmutableMetaField(
                                field.getName(), 
                                field.getIdentifier(), 
                                field.getType());
                        
                        if (!existsColumn(docPart.getIdentifier(), field.getIdentifier(), existingTables)) {
                            throw new InvalidDatabaseSchemaException(schemaName, "Field "+field.getCollection()+"."
                                    +field.getTableRefValue(tableRefFactory)+"."+field.getName()+" in database "+database+" is associated with field "+field.getIdentifier()
                                    +" but there is no field with that name in table "
                                    +schemaName+"."+docPart.getIdentifier());
                        }
                        if (!existsColumnWithType(docPart.getIdentifier(), field.getIdentifier(), 
                                databaseInterface.getDataType(field.getType()), existingTables)) {
                            //TODO: some types can not be recognized using meta data
                            //throw new InvalidDatabaseSchemaException(schemaName, "Field "+field.getCollection()+"."
                            //        +field.getTableRefValue()+"."+field.getName()+" in database "+database+" is associated with field "+field.getIdentifier()
                            //        +" and type "+databaseInterface.getDataType(field.getType()).getTypeName()
                            //        +" but the field "+schemaName+"."+docPart.getIdentifier()+"."+field.getIdentifier()
                            //        +" has a different type "+getColumnType(docPart.getIdentifier(), field.getIdentifier(), existingTables).getTypeName());
                        }
                        metaDocPartBuilder.add(metaField);
                    }
                    metaCollectionBuilder.add(metaDocPartBuilder.build());
                }
                
                metaDatabaseBuilder.add(metaCollectionBuilder.build());
            }
            
            Map<String, MetaDocPartRecord<Object>> docParts = dsl
                    .selectFrom(docPartTable)
                    .where(docPartTable.DATABASE.eq(database))
                    .fetchMap(docPartTable.IDENTIFIER);
            List<MetaFieldRecord<Object>> fields = dsl
                    .selectFrom(fieldTable)
                    .where(fieldTable.DATABASE.eq(database))
                    .fetch();
            IdentifierHelper identifierHelper = new IdentifierHelper(databaseInterface);
            for (Table<?> table : existingTables) {
                if (!docParts.containsKey(table.getName())) {
                    throw new InvalidDatabaseSchemaException(schemaName, "Table "+schemaName+"."+table.getName()
                            +" has no container associated for database "+database);
                }
                
                MetaDocPartRecord<Object> docPart = docParts.get(table.getName());
                for (Field<?> existingField : table.fields()) {
                    if (identifierHelper.isSpecialColumn(existingField.getName())) {
                        continue;
                    }
                    if (!containsField(existingField, docPart.getCollection(), docPart.getTableRefValue(tableRefFactory), fields, tableRefFactory)) {
                        throw new InvalidDatabaseSchemaException(schemaName, "Column "+schemaName+"."+table.getName()
                        +"."+existingField.getName()+" has no field associated for database "+database);
                    }
                }
            }
            
            metaSnapshotBuilder.add(metaDatabaseBuilder.build());
        }
        
        return metaSnapshotBuilder.build();
    }
    
    private boolean existsTable(String tableName, Iterable<? extends Table<?>> tables) {
        for (Table<?> table : tables) {
            if (table.getName().equals(tableName)) {
                return true;
            }
        }
        return false;
    }

    private boolean existsColumn(String tableName, String columnName, Iterable<? extends Table<?>> tables) {
        for (Table<?> table : tables) {
            if (table.getName().equals(tableName)) {
                for (Field<?> field : table.fields()) {
                    if (field.getName().equals(columnName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean existsColumnWithType(String tableName, String columnName, DataType<?> columnType, Iterable<? extends Table<?>> tables) {
        for (Table<?> table : tables) {
            if (table.getName().equals(tableName)) {
                for (Field<?> field : table.fields()) {
                    if (field.getName().equals(columnName)) {
                        if (field.getDataType().getSQLType() == columnType.getSQLType() &&
                            field.getDataType().getCastTypeName().equals(columnType.getCastTypeName())) {
                            return true;
                        } else {
                            return false;
                        }
                    }
                }
            }
        }
        return false;
    }

    private DataType<?> getColumnType(String tableName, String columnName, Iterable<? extends Table<?>> tables) {
        for (Table<?> table : tables) {
            if (table.getName().equals(tableName)) {
                for (Field<?> field : table.fields()) {
                    if (field.getName().equals(columnName)) {
                        return field.getDataType();
                    }
                }
            }
        }
        return null;
    }
    
    private boolean containsField(Field<?> existingField, String collection, TableRef tableRef, Iterable<MetaFieldRecord<Object>> fields, TableRefFactory tableRefFactory) {
        for (MetaFieldRecord<?> field : fields) {
            if (collection.equals(field.getCollection()) &&
                    tableRef.equals(field.getTableRefValue(tableRefFactory)) &&
                    existingField.getName().equals(field.getIdentifier())) {
                return true;
            }
        }
        return false;
    }
    
    public static void checkDatabaseSchema(Schema schema) throws InvalidDatabaseSchemaException {
        //TODO: improve checks
    }
    
	private Map<String,Map<String,Map<TableRef,Integer>>> loadRowIds(DSLContext dsl, MetaSnapshot snapshot) {
		Map<String,Map<String,Map<TableRef,Integer>>> megaMap = new HashMap<>();
		snapshot.streamMetaDatabases().forEach(db -> {
			Map<String,Map<TableRef,Integer>> collMap = new HashMap<>();
			megaMap.put(db.getName(), collMap);
			db.streamMetaCollections().forEach(collection -> {
				Map<TableRef,Integer> tableRefMap = new HashMap<>();
				collMap.put(collection.getName(), tableRefMap);
				collection.streamContainedMetaDocParts().forEach(metaDocPart -> {
					TableRef tableRef = metaDocPart.getTableRef();
					try {
						Integer lastRowIUsed = databaseInterface.getLastRowIUsed(dsl, db, collection, metaDocPart);
						tableRefMap.put(tableRef, lastRowIUsed);
					} catch (Exception e) {
						//TODO: process the exception
						e.printStackTrace();
					}
					
				});
			});
		});
		return megaMap;
	}
}
