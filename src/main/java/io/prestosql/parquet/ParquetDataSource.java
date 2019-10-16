/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.parquet;

import io.prestosql.parquet.reader.MetadataReader;
import org.apache.parquet.format.*;
import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.metadata.*;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Types;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static io.prestosql.parquet.ParquetValidationUtils.validateParquet;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.apache.parquet.format.Util.readFileMetaData;

public class ParquetDataSource implements Closeable
{
    private final ParquetDataSourceId id;//FIXME
    private final String pathToParquetFile;
    private final FileChannel parFile;
    private final long size;
    private long readTimeNanos;
    private long readBytes;

    public ParquetDataSource(String pathToParquetFile) {
        try {
            this.pathToParquetFile = pathToParquetFile;
            this.id = new ParquetDataSourceId(pathToParquetFile);
            this.parFile = FileChannel.open(Paths.get(pathToParquetFile), StandardOpenOption.READ);
            this.size = parFile.size();
        } catch (IOException e) {
            throw new UnsupportedOperationException("can not find " + pathToParquetFile);
        }
    }

    public ParquetDataSourceId getId() {
        return id;
    }

    public final long getReadBytes() {
        return readBytes;
    }

    public long getReadTimeNanos() {
        return readTimeNanos;
    }

    public final long getSize() {
        return size;
    }

    public void close()
            throws IOException {
        parFile.close();
    }

    public final void readFully(long position, byte[] buffer) {
        readFully(position, buffer, 0, buffer.length);
    }

    public final void readFully(long position, byte[] buffer, int bufferOffset, int bufferLength) {
        readBytes += bufferLength;

        long start = System.nanoTime();
        try {
            parFile.position(position);
            parFile.read(ByteBuffer.wrap(buffer, bufferOffset, bufferLength));
        } catch (IOException e) {
            throw new UnsupportedOperationException(e);
        }
        //
        long currentReadTimeNanos = System.nanoTime() - start;
        readTimeNanos += currentReadTimeNanos;
    }


    private static final int PARQUET_METADATA_LENGTH = 4;
    private static final byte[] MAGIC = "PAR1".getBytes(US_ASCII);
    private static final ParquetMetadataConverter PARQUET_METADATA_CONVERTER = new ParquetMetadataConverter();

    //===============================meta reader==========================
    public ParquetMetadata readFooter() throws IOException {
        // Parquet File Layout:
        //
        // MAGIC
        // variable: Data
        // variable: Metadata
        // 4 bytes: MetadataLength
        // MAGIC

        validateParquet(size >= MAGIC.length + PARQUET_METADATA_LENGTH + MAGIC.length, "%s is not a valid Parquet File", pathToParquetFile);
        long metadataLengthIndex = size - PARQUET_METADATA_LENGTH - MAGIC.length;

        InputStream footerStream = readFully(metadataLengthIndex, PARQUET_METADATA_LENGTH + MAGIC.length);
        int metadataLength = readIntLittleEndian(footerStream);

        byte[] magic = new byte[MAGIC.length];
        footerStream.read(magic);
        validateParquet(Arrays.equals(MAGIC, magic), "Not valid Parquet file: %s expected magic number: %s got: %s", pathToParquetFile, Arrays.toString(MAGIC), Arrays.toString(magic));

        long metadataIndex = metadataLengthIndex - metadataLength;
        validateParquet(
                metadataIndex >= MAGIC.length && metadataIndex < metadataLengthIndex,
                "Corrupted Parquet file: %s metadata index: %s out of range",
                pathToParquetFile,
                metadataIndex);
        InputStream metadataStream = readFully(metadataIndex, metadataLength);
        FileMetaData fileMetaData = readFileMetaData(metadataStream);
        List<SchemaElement> schema = fileMetaData.getSchema();
        validateParquet(!schema.isEmpty(), "Empty Parquet schema in file: %s", pathToParquetFile);

        MessageType messageType = readParquetSchema(schema);
        List<BlockMetaData> blocks = new ArrayList<>();
        List<RowGroup> rowGroups = fileMetaData.getRow_groups();
        if (rowGroups != null) {
            for (RowGroup rowGroup : rowGroups) {
                BlockMetaData blockMetaData = new BlockMetaData();
                blockMetaData.setRowCount(rowGroup.getNum_rows());
                blockMetaData.setTotalByteSize(rowGroup.getTotal_byte_size());
                List<ColumnChunk> columns = rowGroup.getColumns();
                validateParquet(!columns.isEmpty(), "No columns in row group: %s", rowGroup);
                String filePath = columns.get(0).getFile_path();
                for (ColumnChunk columnChunk : columns) {
                    validateParquet(
                            (filePath == null && columnChunk.getFile_path() == null)
                                    || (filePath != null && filePath.equals(columnChunk.getFile_path())),
                            "all column chunks of the same row group must be in the same file");
                    ColumnMetaData metaData = columnChunk.meta_data;
                    String[] path = metaData.path_in_schema.stream()
                            .map(value -> value.toLowerCase(Locale.ENGLISH))
                            .toArray(String[]::new);
                    ColumnPath columnPath = ColumnPath.get(path);
                    PrimitiveType primitiveType = messageType.getType(columnPath.toArray()).asPrimitiveType();
                    ColumnChunkMetaData column = ColumnChunkMetaData.get(
                            columnPath,
                            primitiveType,
                            CompressionCodecName.fromParquet(metaData.codec),
                            PARQUET_METADATA_CONVERTER.convertEncodingStats(metaData.encoding_stats),
                            readEncodings(metaData.encodings),
                            MetadataReader.readStats(metaData.statistics, primitiveType.getPrimitiveTypeName()),
                            metaData.data_page_offset,
                            metaData.dictionary_page_offset,
                            metaData.num_values,
                            metaData.total_compressed_size,
                            metaData.total_uncompressed_size);
                    blockMetaData.addColumn(column);
                }
                blockMetaData.setPath(filePath);
                blocks.add(blockMetaData);
            }
        }

        Map<String, String> keyValueMetaData = new HashMap<>();
        List<KeyValue> keyValueList = fileMetaData.getKey_value_metadata();
        if (keyValueList != null) {
            for (KeyValue keyValue : keyValueList) {
                keyValueMetaData.put(keyValue.key, keyValue.value);
            }
        }
        return new ParquetMetadata(new org.apache.parquet.hadoop.metadata.FileMetaData(messageType, keyValueMetaData, fileMetaData.getCreated_by()), blocks);
    }

    private static MessageType readParquetSchema(List<SchemaElement> schema) {
        Iterator<SchemaElement> schemaIterator = schema.iterator();
        SchemaElement rootSchema = schemaIterator.next();
        Types.MessageTypeBuilder builder = Types.buildMessage();
        readTypeSchema(builder, schemaIterator, rootSchema.getNum_children());
        return builder.named(rootSchema.name);
    }

    private static void readTypeSchema(Types.GroupBuilder<?> builder, Iterator<SchemaElement> schemaIterator, int typeCount) {
        for (int i = 0; i < typeCount; i++) {
            SchemaElement element = schemaIterator.next();
            Types.Builder<?, ?> typeBuilder;
            if (element.type == null) {
                typeBuilder = builder.group(org.apache.parquet.schema.Type.Repetition.valueOf(element.repetition_type.name()));
                readTypeSchema((Types.GroupBuilder<?>) typeBuilder, schemaIterator, element.num_children);
            } else {
                Types.PrimitiveBuilder<?> primitiveBuilder = builder.primitive(getTypeName(element.type), org.apache.parquet.schema.Type.Repetition.valueOf(element.repetition_type.name()));
                if (element.isSetType_length()) {
                    primitiveBuilder.length(element.type_length);
                }
                if (element.isSetPrecision()) {
                    primitiveBuilder.precision(element.precision);
                }
                if (element.isSetScale()) {
                    primitiveBuilder.scale(element.scale);
                }
                typeBuilder = primitiveBuilder;
            }

            if (element.isSetConverted_type()) {
                typeBuilder.as(getOriginalType(element.converted_type));
            }
            if (element.isSetField_id()) {
                typeBuilder.id(element.field_id);
            }
            typeBuilder.named(element.name.toLowerCase(Locale.ENGLISH));
        }
    }

    private static Set<org.apache.parquet.column.Encoding> readEncodings(List<Encoding> encodings) {
        Set<org.apache.parquet.column.Encoding> columnEncodings = new HashSet<>();
        for (Encoding encoding : encodings) {
            columnEncodings.add(org.apache.parquet.column.Encoding.valueOf(encoding.name()));
        }
        return Collections.unmodifiableSet(columnEncodings);
    }

    private static PrimitiveType.PrimitiveTypeName getTypeName(Type type) {
        switch (type) {
            case BYTE_ARRAY:
                return PrimitiveType.PrimitiveTypeName.BINARY;
            case INT64:
                return PrimitiveType.PrimitiveTypeName.INT64;
            case INT32:
                return PrimitiveType.PrimitiveTypeName.INT32;
            case BOOLEAN:
                return PrimitiveType.PrimitiveTypeName.BOOLEAN;
            case FLOAT:
                return PrimitiveType.PrimitiveTypeName.FLOAT;
            case DOUBLE:
                return PrimitiveType.PrimitiveTypeName.DOUBLE;
            case INT96:
                return PrimitiveType.PrimitiveTypeName.INT96;
            case FIXED_LEN_BYTE_ARRAY:
                return PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY;
            default:
                throw new IllegalArgumentException("Unknown type " + type);
        }
    }

    private static OriginalType getOriginalType(ConvertedType type) {
        switch (type) {
            case UTF8:
                return OriginalType.UTF8;
            case MAP:
                return OriginalType.MAP;
            case MAP_KEY_VALUE:
                return OriginalType.MAP_KEY_VALUE;
            case LIST:
                return OriginalType.LIST;
            case ENUM:
                return OriginalType.ENUM;
            case DECIMAL:
                return OriginalType.DECIMAL;
            case DATE:
                return OriginalType.DATE;
            case TIME_MILLIS:
                return OriginalType.TIME_MILLIS;
            case TIMESTAMP_MILLIS:
                return OriginalType.TIMESTAMP_MILLIS;
            case INTERVAL:
                return OriginalType.INTERVAL;
            case INT_8:
                return OriginalType.INT_8;
            case INT_16:
                return OriginalType.INT_16;
            case INT_32:
                return OriginalType.INT_32;
            case INT_64:
                return OriginalType.INT_64;
            case UINT_8:
                return OriginalType.UINT_8;
            case UINT_16:
                return OriginalType.UINT_16;
            case UINT_32:
                return OriginalType.UINT_32;
            case UINT_64:
                return OriginalType.UINT_64;
            case JSON:
                return OriginalType.JSON;
            case BSON:
                return OriginalType.BSON;
            case TIMESTAMP_MICROS:
                return OriginalType.TIMESTAMP_MICROS;
            case TIME_MICROS:
                return OriginalType.TIME_MICROS;
            default:
                throw new IllegalArgumentException("Unknown converted type " + type);
        }
    }

    private static int readIntLittleEndian(InputStream in)
            throws IOException {
        int ch1 = in.read();
        int ch2 = in.read();
        int ch3 = in.read();
        int ch4 = in.read();
        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new EOFException();
        }
        return ((ch4 << 24) + (ch3 << 16) + (ch2 << 8) + (ch1));
    }

    private InputStream readFully(long position, int length)
            throws IOException
    {
        byte[] buffer = new byte[length];
        readFully(position, buffer);
        return new ByteArrayInputStream(buffer);
    }
}
