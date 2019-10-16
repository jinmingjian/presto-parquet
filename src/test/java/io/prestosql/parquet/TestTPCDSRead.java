package io.prestosql.parquet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import io.prestosql.parquet.predicate.Predicate;
import io.prestosql.parquet.reader.MetadataReader;
import io.prestosql.parquet.reader.ParquetReader;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.NamedTypeSignature;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.TypeSignatureParameter;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.ColumnIO;
import org.apache.parquet.io.GroupColumnIO;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.PrimitiveColumnIO;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.parquet.ParquetTypeUtils.getColumnIO;
import static io.prestosql.parquet.ParquetTypeUtils.lookupColumnByName;
import static org.apache.parquet.io.ColumnIOUtil.columnDefinitionLevel;
import static org.apache.parquet.io.ColumnIOUtil.columnRepetitionLevel;
import static org.apache.parquet.schema.Type.Repetition.OPTIONAL;

public class TestTPCDSRead {
    public static void main(String[] args) throws IOException {
        //        ss_item_sk
//                ,ss_ticket_number
//                ,ss_customer_sk
//                  ,case when sr_return_quantity is not null then (ss_quantity-sr_return_quantity)*ss_sales_price
        String[] readFields = {"ss_item_sk",
                "ss_ticket_number",
                "ss_customer_sk",
                "ss_quantity",
                "ss_sales_price"};
        long split_start = 0;
        long split_length = 13318383053L;



        ParquetDataSource dataSource = new ParquetDataSource(
                "/cent_home/data_tpcds/SF100_parquet/store_sales/parquet-1-0.parquet"
        );
        ParquetMetadata parquetMetadata = dataSource.readFooter();
        FileMetaData fileMetaData = parquetMetadata.getFileMetaData();
        MessageType fileSchema = fileMetaData.getSchema();


        List<Type> readTypes = Arrays.stream(readFields)
                .map((s) -> fileSchema.getType(s)).collect(toImmutableList());

        MessageType requestedSchema = new MessageType(fileSchema.getName(), readTypes);

        ImmutableList.Builder<BlockMetaData> footerBlocks = ImmutableList.builder();
        for (BlockMetaData block : parquetMetadata.getBlocks()) {
            long firstDataPage = block.getColumns().get(0).getFirstDataPageOffset();
            if (firstDataPage >= split_start && firstDataPage < split_start + split_length) {
                footerBlocks.add(block);
            }
        }

//        Map<List<String>, RichColumnDescriptor> descriptorsByPath = getDescriptors(fileSchema, requestedSchema);
//        TupleDomain<ColumnDescriptor> parquetTupleDomain = getParquetTupleDomain(descriptorsByPath, effectivePredicate);
//        Predicate parquetPredicate = buildPredicate(requestedSchema, parquetTupleDomain, descriptorsByPath);
//        ParquetDataSource finalDataSource = dataSource;
//        ImmutableList.Builder<BlockMetaData> blocks = ImmutableList.builder();
//        for (BlockMetaData block : footerBlocks.build()) {
//            if (predicateMatches(parquetPredicate, block, finalDataSource, descriptorsByPath, parquetTupleDomain, failOnCorruptedParquetStatistics)) {
//                blocks.add(block);
//            }
//        }
        MessageColumnIO messageColumnIO = getColumnIO(fileSchema, requestedSchema);
        ParquetReader parquetReader = new ParquetReader(
                messageColumnIO,
                footerBlocks.build(),
                dataSource);

        int batchSize = parquetReader.nextBatch();

        for (PrimitiveColumnIO col : messageColumnIO.getLeaves()) {
            parquetReader.readBlock(col.getId(), col.getColumnDescriptor());
            System.out.println(batchSize);
        }

    }

    public static Field constructField(Type type, ColumnIO columnIO) {
        boolean required = columnIO.getType().getRepetition() != OPTIONAL;
        int repetitionLevel = columnRepetitionLevel(columnIO);
        int definitionLevel = columnDefinitionLevel(columnIO);
//        if (type instanceof RowType) {
//            GroupColumnIO groupColumnIO = (GroupColumnIO) columnIO;
//            List<Type> parameters = type.getTypeParameters();
//            ImmutableList.Builder<Optional<Field>> fieldsBuilder = ImmutableList.builder();
//            List<TypeSignatureParameter> fields = type.getTypeSignature().getParameters();
//            boolean structHasParameters = false;
//            for (int i = 0; i < fields.size(); i++) {
//                NamedTypeSignature namedTypeSignature = fields.get(i).getNamedTypeSignature();
//                String name = namedTypeSignature.getName().get().toLowerCase(Locale.ENGLISH);
//                Optional<Field> field = constructField(parameters.get(i), lookupColumnByName(groupColumnIO, name));
//                structHasParameters |= field.isPresent();
//                fieldsBuilder.add(field);
//            }
//            if (structHasParameters) {
//                return new GroupField(type, repetitionLevel, definitionLevel, required, fieldsBuilder.build());
//            }
//            return Optional.empty();
//        }
        return null;
    }
}
