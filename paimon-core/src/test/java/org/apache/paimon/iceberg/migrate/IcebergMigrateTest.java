/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.iceberg.migrate;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.DataFormatTestUtil;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.fs.Path;
import org.apache.paimon.iceberg.IcebergOptions;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.TableRead;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.paimon.options.CatalogOptions.CACHE_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/** Tests for {@link IcebergMigrator}. */
public class IcebergMigrateTest {
    @TempDir java.nio.file.Path iceTempDir;
    @TempDir java.nio.file.Path paiTempDir;

    Catalog paiCatalog;

    String iceDatabase = "ice_db";
    String iceTable = "ice_t";

    String paiDatabase = "pai_db";
    String paiTable = "pai_t";

    Schema iceSchema =
            new Schema(
                    Types.NestedField.required(1, "k", Types.IntegerType.get()),
                    Types.NestedField.required(2, "v", Types.IntegerType.get()),
                    Types.NestedField.required(3, "dt", Types.StringType.get()),
                    Types.NestedField.required(4, "hh", Types.StringType.get()));
    Schema iceDeleteSchema =
            new Schema(
                    Types.NestedField.required(1, "k", Types.IntegerType.get()),
                    Types.NestedField.optional(2, "v", Types.IntegerType.get()));

    PartitionSpec icePartitionSpec =
            PartitionSpec.builderFor(iceSchema).identity("dt").identity("hh").build();

    Map<String, String> icebergProperties = new HashMap<>();

    @BeforeEach
    public void beforeEach() throws Exception {
        paiCatalog = createPaimonCatalog();
        icebergProperties.put(IcebergOptions.METADATA_ICEBERG_STORAGE.key(), "hadoop-catalog");
        icebergProperties.put("iceberg_warehouse", iceTempDir.toString());
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testMigrateOnlyAdd(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        writeInitialData(icebergTable, format, isPartitioned);

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();
        icebergMigrator.renameTable(false);

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(iceDatabase, iceTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        Stream.of(
                                        "Record(1, 1, 20240101, 00)",
                                        "Record(2, 2, 20240101, 00)",
                                        "Record(1, 1, 20240101, 01)",
                                        "Record(2, 2, 20240101, 01)")
                                .collect(Collectors.toList()));

        // verify iceberg table has been deleted
        assertThat(paimonTable.fileIO().exists(new Path(icebergTable.location()))).isFalse();
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testMigrateAddAndDelete(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        writeInitialData(icebergTable, format, isPartitioned);

        // the file written with records2 will be deleted and generate a delete manifest entry, not
        // a delete file
        icebergTable.newDelete().deleteFromRowFilter(Expressions.equal("hh", "00")).commit();

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        Stream.of("Record(1, 1, 20240101, 01)", "Record(2, 2, 20240101, 01)")
                                .collect(Collectors.toList()));
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testMigrateWithDeleteFile(boolean isPartitioned) throws Exception {
        // only support create delete file with parquet format
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        List<GenericRecord> records1 =
                Stream.of(
                                toIcebergRecord(1, 1, "20240101", "00"),
                                toIcebergRecord(2, 2, "20240101", "00"))
                        .collect(Collectors.toList());
        List<GenericRecord> deleteRecords1 =
                Collections.singletonList(toIcebergRecord(1, 1, iceDeleteSchema));

        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, records1, "20240101", "00");
            writeEqualityDeleteFile(icebergTable, deleteRecords1, "20240101", "00");
        } else {
            writeRecordsToIceberg(icebergTable, format, records1);
            writeEqualityDeleteFile(icebergTable, deleteRecords1);
        }

        List<GenericRecord> records2 =
                Stream.of(
                                toIcebergRecord(1, 1, "20240101", "01"),
                                toIcebergRecord(2, 2, "20240101", "01"))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, records2, "20240101", "01");
        } else {
            writeRecordsToIceberg(icebergTable, format, records2);
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());

        assertThatThrownBy(icebergMigrator::executeMigrate)
                .rootCause()
                .isInstanceOf(RuntimeException.class)
                .hasMessage(
                        "IcebergMigrator don't support analyzing manifest file with 'DELETE' content.");
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testMigrateWithRandomIcebergData(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";

        int numRounds = 50;
        int numRecords = 20;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<GenericRecord> expectRecords = new ArrayList<>();
        for (int i = 0; i < numRounds; i++) {
            List<GenericRecord> records = new ArrayList<>();
            String dt = Integer.toString(random.nextInt(20240101, 20240104));
            String hh = Integer.toString(random.nextInt(3));
            for (int j = 0; j < numRecords; j++) {
                records.add(toIcebergRecord(random.nextInt(100), random.nextInt(100), dt, hh));
            }
            expectRecords.addAll(records);
            if (isPartitioned) {
                writeRecordsToIceberg(icebergTable, format, records, dt, hh);
            } else {
                writeRecordsToIceberg(icebergTable, format, records);
            }
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        expectRecords.stream()
                                .map(GenericRecord::toString)
                                .collect(Collectors.toList()));
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testDeleteColumn(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        writeInitialData(icebergTable, format, isPartitioned);

        icebergTable.updateSchema().deleteColumn("v").commit();
        Schema newIceSchema = icebergTable.schema();
        List<GenericRecord> addedRecords =
                Stream.of(
                                toIcebergRecord(3, "20240101", "00", newIceSchema),
                                toIcebergRecord(4, "20240101", "00", newIceSchema))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, addedRecords, "20240101", "00");
        } else {
            writeRecordsToIceberg(icebergTable, format, addedRecords);
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        Stream.of(
                                        "Record(1, 20240101, 00)",
                                        "Record(2, 20240101, 00)",
                                        "Record(1, 20240101, 01)",
                                        "Record(2, 20240101, 01)",
                                        "Record(3, 20240101, 00)",
                                        "Record(4, 20240101, 00)")
                                .collect(Collectors.toList()));
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testRenameColumn(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        writeInitialData(icebergTable, format, isPartitioned);

        icebergTable.updateSchema().renameColumn("v", "v2").commit();
        Schema newIceSchema = icebergTable.schema();
        List<GenericRecord> addedRecords =
                Stream.of(
                                toIcebergRecord(newIceSchema, 3, 3, "20240101", "00"),
                                toIcebergRecord(newIceSchema, 4, 4, "20240101", "00"))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, addedRecords, "20240101", "00");
        } else {
            writeRecordsToIceberg(icebergTable, format, addedRecords);
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        Stream.of(
                                        "Record(1, 1, 20240101, 00)",
                                        "Record(2, 2, 20240101, 00)",
                                        "Record(1, 1, 20240101, 01)",
                                        "Record(2, 2, 20240101, 01)",
                                        "Record(3, 3, 20240101, 00)",
                                        "Record(4, 4, 20240101, 00)")
                                .collect(Collectors.toList()));
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testAddColumn(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        writeInitialData(icebergTable, format, isPartitioned);

        icebergTable.updateSchema().addColumn("v2", Types.IntegerType.get()).commit();
        Schema newIceSchema = icebergTable.schema();
        List<GenericRecord> addedRecords =
                Stream.of(
                                toIcebergRecord(newIceSchema, 3, 3, "20240101", "00", 3),
                                toIcebergRecord(newIceSchema, 4, 4, "20240101", "00", 4))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, addedRecords, "20240101", "00");
        } else {
            writeRecordsToIceberg(icebergTable, format, addedRecords);
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        Stream.of(
                                        "Record(1, 1, 20240101, 00, NULL)",
                                        "Record(2, 2, 20240101, 00, NULL)",
                                        "Record(1, 1, 20240101, 01, NULL)",
                                        "Record(2, 2, 20240101, 01, NULL)",
                                        "Record(3, 3, 20240101, 00, 3)",
                                        "Record(4, 4, 20240101, 00, 4)")
                                .collect(Collectors.toList()));
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testReorderColumn(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        writeInitialData(icebergTable, format, isPartitioned);

        icebergTable.updateSchema().moveAfter("v", "hh").commit();
        Schema newIceSchema = icebergTable.schema();
        List<GenericRecord> addedRecords =
                Stream.of(
                                toIcebergRecord(newIceSchema, 3, "20240101", "00", 3),
                                toIcebergRecord(newIceSchema, 4, "20240101", "00", 4))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, addedRecords, "20240101", "00");
        } else {
            writeRecordsToIceberg(icebergTable, format, addedRecords);
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        Stream.of(
                                        "Record(1, 20240101, 00, 1)",
                                        "Record(2, 20240101, 00, 2)",
                                        "Record(1, 20240101, 01, 1)",
                                        "Record(2, 20240101, 01, 2)",
                                        "Record(3, 20240101, 00, 3)",
                                        "Record(4, 20240101, 00, 4)")
                                .collect(Collectors.toList()));
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testUpdateColumn(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        String format = "parquet";
        writeInitialData(icebergTable, format, isPartitioned);

        icebergTable.updateSchema().updateColumn("v", Types.LongType.get()).commit();
        Schema newIceSchema = icebergTable.schema();
        List<GenericRecord> addedRecords =
                Stream.of(
                                toIcebergRecord(newIceSchema, 3, 3L, "20240101", "00"),
                                toIcebergRecord(newIceSchema, 4, 3L, "20240101", "00"))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, addedRecords, "20240101", "00");
        } else {
            writeRecordsToIceberg(icebergTable, format, addedRecords);
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("Record(%s)", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        Stream.of(
                                        "Record(1, 1, 20240101, 00)",
                                        "Record(2, 2, 20240101, 00)",
                                        "Record(1, 1, 20240101, 01)",
                                        "Record(2, 2, 20240101, 01)",
                                        "Record(3, 3, 20240101, 00)",
                                        "Record(4, 3, 20240101, 00)")
                                .collect(Collectors.toList()));
    }

    @ParameterizedTest(name = "isPartitioned = {0}")
    @ValueSource(booleans = {true, false})
    public void testMigrateWithRandomIcebergEvolution(boolean isPartitioned) throws Exception {
        Table icebergTable = createIcebergTable(isPartitioned);
        icebergTable.updateSchema().addColumn("v2", Types.IntegerType.get()).commit();
        String format = "parquet";
        List<String> index = new LinkedList<>(Arrays.asList("k", "v", "dt", "hh", "v2"));

        int numRounds = 20;
        int numRecords = 10;
        List<Integer> ops = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean isTypeChange = false;
        List<List<String>> expectRecords = new ArrayList<>();

        for (int i = 0; i < numRounds; i++) {
            List<GenericRecord> records = new ArrayList<>();
            String dt = Integer.toString(random.nextInt(20240101, 20240104));
            String hh = Integer.toString(random.nextInt(3));

            if ((i + 1) % 4 == 0 && !ops.isEmpty()) {
                switch (ops.remove(random.nextInt(ops.size()))) {
                    case 1:
                        icebergTable
                                .updateSchema()
                                .addColumn("v3", Types.IntegerType.get())
                                .commit();
                        for (List<String> record : expectRecords) {
                            record.add("NULL");
                        }
                        index.add("v3");
                        break;
                    case 2:
                        icebergTable.updateSchema().renameColumn("v", "vv").commit();
                        break;
                    case 3:
                        icebergTable.updateSchema().deleteColumn("v2").commit();
                        int v2Idx = index.indexOf("v2");
                        for (List<String> record : expectRecords) {
                            record.remove(v2Idx);
                        }
                        index.remove(v2Idx);
                        break;
                    case 4:
                        icebergTable.updateSchema().moveAfter("k", "hh").commit();
                        int kIdx = index.indexOf("k");
                        int hhIdx = index.indexOf("hh");
                        for (List<String> record : expectRecords) {
                            String k = record.remove(kIdx);
                            record.add(hhIdx, k);
                        }
                        index.remove(kIdx);
                        index.add(hhIdx, "k");
                        break;
                    case 5:
                        icebergTable
                                .updateSchema()
                                .updateColumn("k", Types.LongType.get())
                                .commit();
                        isTypeChange = true;
                        break;
                    default:
                        throw new IllegalStateException("Unknown operation");
                }
            }
            for (int j = 0; j < numRecords; j++) {
                List<String> recordString = new ArrayList<>();
                GenericRecord record = GenericRecord.create(icebergTable.schema());
                for (int idx = 0; idx < index.size(); idx++) {
                    String field = index.get(idx);
                    if (field.equals("dt")) {
                        record.set(idx, dt);
                        recordString.add(dt);
                    } else if (field.equals("hh")) {
                        record.set(idx, hh);
                        recordString.add(hh);
                    } else {
                        int value = random.nextInt(100);
                        if (field.equals("k") && isTypeChange) {
                            record.set(idx, (long) value);
                        } else {
                            record.set(idx, value);
                        }
                        recordString.add(String.valueOf(value));
                    }
                }
                records.add(record);
                expectRecords.add(recordString);
            }

            if (isPartitioned) {
                writeRecordsToIceberg(icebergTable, format, records, dt, hh);
            } else {
                writeRecordsToIceberg(icebergTable, format, records);
            }
        }

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        System.out.println();
        assertThat(
                        paiResults.stream()
                                .map(row -> String.format("[%s]", row))
                                .collect(Collectors.toList()))
                .hasSameElementsAs(
                        expectRecords.stream().map(List::toString).collect(Collectors.toList()));
    }

    @Test
    public void testAllDataTypes() throws Exception {
        Schema iceAllTypesSchema =
                new Schema(
                        Types.NestedField.required(1, "c1", Types.BooleanType.get()),
                        Types.NestedField.required(2, "c2", Types.IntegerType.get()),
                        Types.NestedField.required(3, "c3", Types.LongType.get()),
                        Types.NestedField.required(4, "c4", Types.FloatType.get()),
                        Types.NestedField.required(5, "c5", Types.DoubleType.get()),
                        Types.NestedField.required(6, "c6", Types.DateType.get()),
                        Types.NestedField.required(7, "c7", Types.StringType.get()),
                        Types.NestedField.required(8, "c9", Types.BinaryType.get()),
                        Types.NestedField.required(9, "c11", Types.DecimalType.of(10, 2)),
                        Types.NestedField.required(10, "c13", Types.TimestampType.withoutZone()),
                        Types.NestedField.required(11, "c14", Types.TimestampType.withZone()));
        Table icebergTable = createIcebergTable(false, iceAllTypesSchema);
        String format = "parquet";
        GenericRecord record =
                toIcebergRecord(
                        iceAllTypesSchema,
                        true,
                        1,
                        1L,
                        1.0F,
                        1.0D,
                        LocalDate.of(2023, 10, 18),
                        "test",
                        ByteBuffer.wrap(new byte[] {1, 2, 3}),
                        new BigDecimal("122.50"),
                        LocalDateTime.now(),
                        OffsetDateTime.now());

        writeRecordsToIceberg(icebergTable, format, Collections.singletonList(record));

        CatalogContext context = CatalogContext.create(new Path(paiTempDir.toString()));
        context.options().set(CACHE_ENABLED, false);
        Catalog catalog = CatalogFactory.createCatalog(context);
        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) catalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(paiResults.size()).isEqualTo(1);
    }

    @Test
    public void testNestedDataTypes() throws Exception {
        // iceberg nested schema
        // table {
        //  1: c1: required list<string>
        //  2: c2: required map<string, int>
        //  3: c3: required struct<11: c31: required int, 12: c32: required string>
        //  4: c4: required map<int, list<struct<13: c41: required int, 14: c42: required string>>>
        // }
        Schema innerType1 =
                new Schema(
                        Types.NestedField.required(11, "c31", Types.IntegerType.get()),
                        Types.NestedField.required(12, "c32", Types.StringType.get()));
        Schema innerType2 =
                new Schema(
                        Types.NestedField.required(13, "c41", Types.IntegerType.get()),
                        Types.NestedField.required(14, "c42", Types.StringType.get()));
        Schema iceNestedTypesSchema =
                new Schema(
                        Types.NestedField.required(
                                1, "c1", Types.ListType.ofRequired(15, Types.StringType.get())),
                        Types.NestedField.required(
                                2,
                                "c2",
                                Types.MapType.ofRequired(
                                        16, 17, Types.StringType.get(), Types.IntegerType.get())),
                        Types.NestedField.required(3, "c3", innerType1.asStruct()),
                        Types.NestedField.required(
                                4,
                                "c4",
                                Types.MapType.ofRequired(
                                        18,
                                        19,
                                        Types.IntegerType.get(),
                                        Types.ListType.ofRequired(20, innerType2.asStruct()))));
        Table icebergTable = createIcebergTable(false, iceNestedTypesSchema);
        String format = "parquet";
        GenericRecord record =
                toIcebergRecord(
                        iceNestedTypesSchema,
                        Arrays.asList("hello", "hi"),
                        new HashMap<String, Integer>() {
                            {
                                put("k1", 1);
                                put("k2", 2);
                            }
                        },
                        toIcebergRecord(innerType1, 1, "test"),
                        new HashMap<Integer, List<GenericRecord>>() {
                            {
                                put(
                                        1,
                                        Collections.singletonList(
                                                toIcebergRecord(innerType2, 11, "test-11")));
                                put(
                                        2,
                                        Arrays.asList(
                                                toIcebergRecord(innerType2, 12, "test-12"),
                                                toIcebergRecord(innerType2, 13, "test-13")));
                            }
                        });

        writeRecordsToIceberg(icebergTable, format, Collections.singletonList(record));

        IcebergMigrator icebergMigrator =
                new IcebergMigrator(
                        paiCatalog,
                        paiDatabase,
                        paiTable,
                        iceDatabase,
                        iceTable,
                        new Options(icebergProperties),
                        1,
                        Collections.emptyMap());
        icebergMigrator.executeMigrate();

        FileStoreTable paimonTable =
                (FileStoreTable) paiCatalog.getTable(Identifier.create(paiDatabase, paiTable));
        List<String> paiResults = getPaimonResult(paimonTable);
        assertThat(paiResults.stream().map(row -> String.format("(%s)", row)))
                .hasSameElementsAs(
                        Collections.singletonList(
                                record.toString().replaceAll("\\bRecord\\b", "")));
    }

    private org.apache.iceberg.catalog.Catalog createIcebergCatalog() {
        Map<String, String> icebergCatalogOptions = new HashMap<>();
        icebergCatalogOptions.put("type", "hadoop");
        icebergCatalogOptions.put("warehouse", iceTempDir.toString());

        return CatalogUtil.buildIcebergCatalog(
                "iceberg_catalog", icebergCatalogOptions, new Configuration());
    }

    private Catalog createPaimonCatalog() {
        CatalogContext context = CatalogContext.create(new Path(paiTempDir.toString()));
        context.options().set(CACHE_ENABLED, false);
        return CatalogFactory.createCatalog(context);
    }

    private Table createIcebergTable(boolean isPartitioned) {
        return createIcebergTable(isPartitioned, iceSchema);
    }

    private Table createIcebergTable(boolean isPartitioned, Schema icebergSchema) {

        org.apache.iceberg.catalog.Catalog icebergCatalog = createIcebergCatalog();
        TableIdentifier icebergIdentifier = TableIdentifier.of(iceDatabase, iceTable);

        if (!isPartitioned) {
            return icebergCatalog
                    .buildTable(icebergIdentifier, icebergSchema)
                    .withPartitionSpec(PartitionSpec.unpartitioned())
                    .create();
        } else {
            return icebergCatalog
                    .buildTable(icebergIdentifier, icebergSchema)
                    .withPartitionSpec(icePartitionSpec)
                    .create();
        }
    }

    private void writeInitialData(Table icebergTable, String format, boolean isPartitioned)
            throws IOException {
        List<GenericRecord> records1 =
                Stream.of(
                                toIcebergRecord(1, 1, "20240101", "00"),
                                toIcebergRecord(2, 2, "20240101", "00"))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, records1, "20240101", "00");
        } else {
            writeRecordsToIceberg(icebergTable, format, records1);
        }

        List<GenericRecord> records2 =
                Stream.of(
                                toIcebergRecord(1, 1, "20240101", "01"),
                                toIcebergRecord(2, 2, "20240101", "01"))
                        .collect(Collectors.toList());
        if (isPartitioned) {
            writeRecordsToIceberg(icebergTable, format, records2, "20240101", "01");
        } else {
            writeRecordsToIceberg(icebergTable, format, records2);
        }
    }

    private GenericRecord toIcebergRecord(Schema icebergSchema, Object... values) {
        GenericRecord record = GenericRecord.create(icebergSchema);
        for (int i = 0; i < values.length; i++) {
            record.set(i, values[i]);
        }
        return record;
    }

    private GenericRecord toIcebergRecord(Object... values) {
        return toIcebergRecord(iceSchema, values);
    }

    private DataWriter<GenericRecord> createIcebergDataWriter(
            Table icebergTable, String format, OutputFile file, String... partitionValues)
            throws IOException {
        Schema schema = icebergTable.schema();
        PartitionSpec partitionSpec = icebergTable.spec();
        PartitionKey partitionKey =
                partitionValues.length == 0
                        ? null
                        : partitionKey(
                                icePartitionSpec,
                                icebergTable,
                                partitionValues[0],
                                partitionValues[1]);
        // currently only support "parquet" format
        switch (format) {
            case "parquet":
                return Parquet.writeData(file)
                        .schema(schema)
                        .createWriterFunc(GenericParquetWriter::buildWriter)
                        .overwrite()
                        .withSpec(partitionSpec)
                        .withPartition(partitionKey)
                        .build();
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    private void writeRecordsToIceberg(
            Table icebergTable,
            String format,
            List<GenericRecord> records,
            String... partitionValues)
            throws IOException {
        String filepath = icebergTable.location() + "/" + UUID.randomUUID();
        OutputFile file = icebergTable.io().newOutputFile(filepath);

        DataWriter<GenericRecord> dataWriter =
                createIcebergDataWriter(icebergTable, format, file, partitionValues);
        try {
            for (GenericRecord r : records) {
                dataWriter.write(r);
            }
        } finally {
            dataWriter.close();
        }
        DataFile dataFile = dataWriter.toDataFile();
        icebergTable.newAppend().appendFile(dataFile).commit();
    }

    private void writeEqualityDeleteFile(
            Table icebergTable, List<GenericRecord> deleteRecords, String... partitionValues)
            throws IOException {
        String filepath = icebergTable.location() + "/" + UUID.randomUUID();
        OutputFile file = icebergTable.io().newOutputFile(filepath);

        EqualityDeleteWriter<GenericRecord> deleteWriter =
                Parquet.writeDeletes(file)
                        .createWriterFunc(GenericParquetWriter::buildWriter)
                        .overwrite()
                        .rowSchema(iceDeleteSchema)
                        .withSpec(PartitionSpec.unpartitioned())
                        .equalityFieldIds(1)
                        .buildEqualityWriter();
        if (partitionValues.length != 0) {
            deleteWriter =
                    Parquet.writeDeletes(file)
                            .createWriterFunc(GenericParquetWriter::buildWriter)
                            .overwrite()
                            .rowSchema(iceDeleteSchema)
                            .withSpec(icePartitionSpec)
                            .withPartition(
                                    partitionKey(
                                            icePartitionSpec,
                                            icebergTable,
                                            partitionValues[0],
                                            partitionValues[1]))
                            .equalityFieldIds(1)
                            .buildEqualityWriter();
        }

        try (EqualityDeleteWriter<GenericRecord> closableWriter = deleteWriter) {
            closableWriter.write(deleteRecords);
        }

        DeleteFile deleteFile = deleteWriter.toDeleteFile();
        icebergTable.newRowDelta().addDeletes(deleteFile).commit();
    }

    private PartitionKey partitionKey(
            PartitionSpec spec, Table icergTable, String... partitionValues) {
        Record record =
                GenericRecord.create(icergTable.schema())
                        .copy(ImmutableMap.of("dt", partitionValues[0], "hh", partitionValues[1]));

        PartitionKey partitionKey = new PartitionKey(spec, icergTable.schema());
        partitionKey.partition(record);

        return partitionKey;
    }

    private List<String> getPaimonResult(FileStoreTable paimonTable) throws Exception {
        List<Split> splits = paimonTable.newReadBuilder().newScan().plan().splits();
        TableRead read = paimonTable.newReadBuilder().newRead();
        try (RecordReader<InternalRow> recordReader = read.createReader(splits)) {
            List<String> result = new ArrayList<>();
            recordReader.forEachRemaining(
                    row ->
                            result.add(
                                    DataFormatTestUtil.toStringWithRowKind(
                                            row, paimonTable.rowType())));
            return result;
        }
    }
}
