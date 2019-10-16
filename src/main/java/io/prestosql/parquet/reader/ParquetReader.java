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
package io.prestosql.parquet.reader;

import io.airlift.units.DataSize;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.parquet.Field;
import io.prestosql.parquet.GroupField;
import io.prestosql.parquet.ParquetCorruptionException;
import io.prestosql.parquet.ParquetDataSource;
import io.prestosql.parquet.PrimitiveField;
import io.prestosql.parquet.RichColumnDescriptor;
import io.prestosql.spi.block.ArrayBlock;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.RowBlock;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.type.*;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.PrimitiveColumnIO;
import org.apache.parquet.schema.DecimalMetadata;
import org.apache.parquet.schema.PrimitiveType;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.parquet.ParquetValidationUtils.validateParquet;
import static io.prestosql.parquet.reader.ListColumnReader.calculateCollectionOffsets;
import static io.prestosql.spi.type.StandardTypes.ARRAY;
import static io.prestosql.spi.type.StandardTypes.MAP;
import static io.prestosql.spi.type.StandardTypes.ROW;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class ParquetReader
        implements Closeable
{
    private static final int MAX_VECTOR_LENGTH = 1024;
    private static final int INITIAL_BATCH_SIZE = 1;
    private static final int BATCH_SIZE_GROWTH_FACTOR = 2;

    private final List<BlockMetaData> blocks;
    private final List<PrimitiveColumnIO> columns;
    private final ParquetDataSource dataSource;

    private int currentBlock;
    private BlockMetaData currentBlockMetadata;
    private long currentPosition;
    private long currentGroupRowCount;
    private long nextRowInGroup;
    private int batchSize;
    private int nextBatchSize = INITIAL_BATCH_SIZE;
    private final PrimitiveColumnReader[] columnReaders;
    private long[] maxBytesPerCell;
    private long maxCombinedBytesPerRow;
    private int maxBatchSize = MAX_VECTOR_LENGTH;

    public ParquetReader(MessageColumnIO messageColumnIO,
            List<BlockMetaData> blocks,
            ParquetDataSource dataSource)
    {
        this.blocks = blocks;
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
        columns = messageColumnIO.getLeaves();
        columnReaders = new PrimitiveColumnReader[columns.size()];
        maxBytesPerCell = new long[columns.size()];
    }

    @Override
    public void close()
            throws IOException
    {
        dataSource.close();
    }

    public long getPosition()
    {
        return currentPosition;
    }

    public int nextBatch()
    {
        if (nextRowInGroup >= currentGroupRowCount && !advanceToNextRowGroup()) {
            return -1;
        }

        batchSize = toIntExact(min(nextBatchSize, maxBatchSize));
        nextBatchSize = min(batchSize * BATCH_SIZE_GROWTH_FACTOR, MAX_VECTOR_LENGTH);
        batchSize = toIntExact(min(batchSize, currentGroupRowCount - nextRowInGroup));

        nextRowInGroup += batchSize;
        currentPosition += batchSize;
        Arrays.stream(columnReaders)
                .forEach(reader -> reader.prepareNextRead(batchSize));
        return batchSize;
    }

    private boolean advanceToNextRowGroup()
    {
        if (currentBlock == blocks.size()) {
            return false;
        }
        currentBlockMetadata = blocks.get(currentBlock);
        currentBlock = currentBlock + 1;

        nextRowInGroup = 0L;
        currentGroupRowCount = currentBlockMetadata.getRowCount();
        initializeColumnReaders();
        return true;
    }

    private ColumnChunk readPrimitive(int fieldId, ColumnDescriptor columnDescriptor)
            throws IOException
    {
        PrimitiveColumnReader columnReader = columnReaders[fieldId];
        if (columnReader.getPageReader() == null) {
            validateParquet(currentBlockMetadata.getRowCount() > 0, "Row group has 0 rows");
            ColumnChunkMetaData metadata = getColumnChunkMetaData(columnDescriptor);
            long startingPosition = metadata.getStartingPos();
            int totalSize = toIntExact(metadata.getTotalSize());
            byte[] buffer = new byte[totalSize];
            dataSource.readFully(startingPosition, buffer);
            ColumnChunkDescriptor descriptor = new ColumnChunkDescriptor(columnDescriptor, metadata, totalSize);
            ParquetColumnChunk columnChunk = new ParquetColumnChunk(descriptor, buffer, 0);
            columnReader.setPageReader(columnChunk.readAllPages());
        }
        PrimitiveType primitiveType = columnDescriptor.getPrimitiveType();
        ColumnChunk columnChunk = columnReader.readPrimitive(getSpiType(primitiveType));

        // update max size per primitive column chunk
        long bytesPerCell = columnChunk.getBlock().getSizeInBytes() / batchSize;
        if (maxBytesPerCell[fieldId] < bytesPerCell) {
            // update batch size
            maxCombinedBytesPerRow = maxCombinedBytesPerRow - maxBytesPerCell[fieldId] + bytesPerCell;
//            maxBatchSize = toIntExact(min(maxBatchSize, max(1, maxReadBlockBytes / maxCombinedBytesPerRow)));
            maxBytesPerCell[fieldId] = bytesPerCell;
        }
        return columnChunk;
    }

    public static final Type getSpiType(PrimitiveType parquetType) {
        switch (parquetType.getPrimitiveTypeName()) {
            case INT32:
                return IntegerType.INTEGER;
            case INT64:
                return BigintType.BIGINT;
            case BINARY:
                return VarcharType.createUnboundedVarcharType();
            default://FIXME
                throw new UnsupportedOperationException("do not support "+parquetType);
        }
    }

    private ColumnChunkMetaData getColumnChunkMetaData(ColumnDescriptor columnDescriptor)
            throws IOException
    {
        for (ColumnChunkMetaData metadata : currentBlockMetadata.getColumns()) {
            if (metadata.getPath().equals(ColumnPath.get(columnDescriptor.getPath()))) {
                return metadata;
            }
        }
        throw new ParquetCorruptionException("Metadata is missing for column: %s", columnDescriptor);
    }

    private void initializeColumnReaders()
    {
        for (PrimitiveColumnIO columnIO : columns) {
            RichColumnDescriptor column = new RichColumnDescriptor(columnIO.getColumnDescriptor(), columnIO.getType().asPrimitiveType());
            columnReaders[columnIO.getId()] = PrimitiveColumnReader.createReader(column);
        }
    }

    public Block readBlock(int fieldId, ColumnDescriptor columnDescriptor)
            throws IOException
    {
        return readPrimitive(fieldId, columnDescriptor).getBlock();
    }


    public ParquetDataSource getDataSource()
    {
        return dataSource;
    }

}
