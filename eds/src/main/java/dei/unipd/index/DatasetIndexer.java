/*
 *  Copyright 2021-2022 University of Padua, Italy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dei.unipd.index;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import utils.Constants;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import dei.unipd.analyze.AnalyzerUtil;

/**
 * Indexer object for indexing the ACORDAR datasets previously mined
 *
 * @author Manuel Barusco (manuel.barusco@studenti.unipd.it)
 * @version 1.00
 * @since 1.00
 */
public class DatasetIndexer {

    /**
     * One megabyte constant
     */
    private static final int MBYTE = 1024 * 1024;

    /**
     * The index writer Lucene object
     */
    private final IndexWriter writer;

    /**
     * The JSON Reader object for reading the datasets json files
     */
    private JsonReader reader;

    /**
     * The directory (and eventually sub-directories) where documents are stored.
     */
    private final Path datasetsDir;

    /**
     * The charset used for encoding documents.
     */
    private final Charset cs;

    /**
     * The total number of documents expected to be indexed.
     */
    private final long expectedDatasets;

    /**
     * The start instant of the indexing.
     */
    private final long start;

    /**
     * The total number of indexed files (datasets)
     */
    private long filesCount;

    /**
     * The total number of indexed documents (datasets)
     */
    private long datasetsCount;

    /**
     * The total number of indexed bytes
     */
    private long bytesCount;

    /**
     * Creates a new indexer
     *
     * @param analyzer                  the {@code Analyzer} to be used in the indexing phase
     * @param ramBufferSizeMB           the size in megabytes of the RAM buffer for indexing documents.
     * @param indexPath                 the directory where to store the index.
     * @param datasetsDirectoryPath     the directory from which datasets have to be read.
     * @param charsetName               the name of the charset used for encoding documents.
     * @param expectedDocs              the total number of datasets expected to be indexed
     * @throws NullPointerException     if any of the parameters is {@code null}.
     * @throws IllegalArgumentException if any of the parameters assumes invalid values.
     */
    public DatasetIndexer(final Analyzer analyzer, final int ramBufferSizeMB,
                          final String indexPath, final String datasetsDirectoryPath,
                          final String charsetName, final long expectedDocs) {

        if (analyzer == null) {
            throw new NullPointerException("Analyzer cannot be null.");
        }

        /*
        if (similarity == null) {
            throw new NullPointerException("Similarity cannot be null.");
        }
        */

        if (ramBufferSizeMB <= 0) {
            throw new IllegalArgumentException("RAM buffer size cannot be less than or equal to zero.");
        }

        //setting up the Lucene IndexWriter object
        final IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        //iwc.setSimilarity(similarity);
        iwc.setRAMBufferSizeMB(ramBufferSizeMB);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        iwc.setCommitOnClose(true);
        iwc.setUseCompoundFile(true);

        if (indexPath == null) {
            throw new NullPointerException("Index path cannot be null.");
        }

        if (indexPath.isEmpty()) {
            throw new IllegalArgumentException("Index path cannot be empty.");
        }

        final Path indexDir = Paths.get(indexPath);

        // if the directory for the index files does not already exist, create it
        if (Files.notExists(indexDir)) {
            try {
                Files.createDirectory(indexDir);
            } catch (Exception e) {
                throw new IllegalArgumentException(String.format("Unable to create directory %s: %s.", indexDir.toAbsolutePath(), e.getMessage()), e);
            }
        }

        if (!Files.isWritable(indexDir)) {
            throw new IllegalArgumentException(String.format("Index directory %s cannot be written.", indexDir.toAbsolutePath()));
        }

        if (!Files.isDirectory(indexDir)) {
            throw new IllegalArgumentException(String.format("%s expected to be a directory where to write the index.", indexDir.toAbsolutePath()));
        }

        if (datasetsDirectoryPath == null) {
            throw new NullPointerException("Datasets path cannot be null.");
        }

        if (datasetsDirectoryPath.isEmpty()) {
            throw new IllegalArgumentException("Datasets path cannot be empty.");
        }

        final Path datasetsDir = Paths.get(datasetsDirectoryPath);
        if (!Files.isReadable(datasetsDir)) {
            throw new IllegalArgumentException(
                    String.format("Datasets directory %s cannot be read.", datasetsDir.toAbsolutePath().toString()));
        }

        if (!Files.isDirectory(datasetsDir)) {
            throw new IllegalArgumentException(
                    String.format("%s expected to be a directory of Datasets.", datasetsDir.toAbsolutePath().toString()));
        }

        this.datasetsDir = datasetsDir;

        if (charsetName == null) {
            throw new NullPointerException("Charset name cannot be null.");
        }

        if (charsetName.isEmpty()) {
            throw new IllegalArgumentException("Charset name cannot be empty.");
        }

        try {
            cs = Charset.forName(charsetName);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Unable to create the charset %s: %s.", charsetName, e.getMessage()), e);
        }

        if (expectedDocs <= 0) {
            throw new IllegalArgumentException(
                    "The expected number of documents to be indexed cannot be less than or equal to zero.");
        }
        this.expectedDatasets = expectedDocs;

        //set to zero the counters
        this.datasetsCount = 0;

        this.bytesCount = 0;

        this.filesCount = 0;

        try {
            writer = new IndexWriter(FSDirectory.open(indexDir), iwc);
        } catch (IOException e) {
            throw new IllegalArgumentException(String.format("Unable to create the index writer in directory %s: %s.",
                    indexDir.toAbsolutePath().toString(), e.getMessage()), e);
        }

        this.start = System.currentTimeMillis();

    }

    /**
     * This method index a single field read in the json file by considering the different types
     * of fields that must be indexed in the document
     *
     * @param reader json reader object
     * @param document lucene document object (in our case it represents a dataset)
     * @param name of the json field read
     */
    public void indexSingleField(JsonReader reader, Document document, String name){
        //check the name of the field
        try {
            if (Objects.equals(name, "dataset_id")) {
                String id = reader.nextString();
                document.add(new DatasetField("dataset_id", id));
            }
            if (Objects.equals(name, "title")) {
                String title = reader.nextString();
                document.add(new DatasetField("title", title));
            }
            if (Objects.equals(name, "description")) {
                String description = reader.nextString();
                document.add(new DatasetField("description", description));
            }
            if (Objects.equals(name, "author")) {
                String author = reader.nextString();
                document.add(new DatasetField("author", author));
            }

            //the tag are splitted and indexed
            if (Objects.equals(name, "tags")) {
                String tags = reader.nextString();
                String[] tagsArray = tags.split(";");
                for (String tag : tagsArray) {
                    document.add(new DatasetField("tags", tag));
                }
            }

            //manage the classes, entities, literals and properties
            if (Objects.equals(name, "classes")) {
                reader.beginArray();
                JsonToken jsonToken;
                while ((jsonToken = reader.peek()) != JsonToken.END_ARRAY) {
                    if (jsonToken == JsonToken.STRING) {
                        document.add(new DatasetField("classes", reader.nextString()));
                    }
                }
                reader.endArray();
            }

            if (Objects.equals(name, "entities")) {
                reader.beginArray();
                JsonToken jsonToken;
                while ((jsonToken = reader.peek()) != JsonToken.END_ARRAY) {
                    if (jsonToken == JsonToken.STRING) {
                        document.add(new DatasetField("entities", reader.nextString()));
                    }
                }
                reader.endArray();
            }

            if (Objects.equals(name, "literals")) {
                reader.beginArray();
                JsonToken jsonToken;
                while ((jsonToken = reader.peek()) != JsonToken.END_ARRAY) {
                    if (jsonToken == JsonToken.STRING) {
                        document.add(new DatasetField("literals", reader.nextString()));
                    }
                }
                reader.endArray();
            }

            if (Objects.equals(name, "properties")) {
                reader.beginArray();
                JsonToken jsonToken;
                while ((jsonToken = reader.peek()) != JsonToken.END_ARRAY) {
                    if (jsonToken == JsonToken.STRING) {
                        document.add(new DatasetField("properties", reader.nextString()));
                    }
                }
                reader.endArray();
            }

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Indexes the datasets.
     *
     * @throws IOException if something goes wrong while indexing.
     */
    public void index() throws IOException {

        System.out.printf("%n#### Start indexing ####%n");

        Files.walkFileTree(datasetsDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                //open only the dataset.json file for all the datasets
                if (file.getFileName().toString().equals("dataset.json")) {

                    //creating the JSON Parser
                    JsonReader reader = new JsonReader(new FileReader(file.toString()));

                    bytesCount += Files.size(file);

                    filesCount += 1;

                    Document doc = new Document(); //Lucene Document

                    try {
                        JsonToken jsonToken;

                        //loop while we not reach the end of the document
                        while ((jsonToken = reader.peek()) != JsonToken.END_DOCUMENT) {
                            if (jsonToken == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject();
                            } else if (jsonToken == JsonToken.END_OBJECT) {
                                reader.endObject();
                            } else if (jsonToken == JsonToken.BEGIN_ARRAY) {
                                reader.beginArray();
                            } else if (jsonToken == JsonToken.END_ARRAY) {
                                reader.endArray();
                            } else if (jsonToken == JsonToken.NAME) {

                                //Add the datasets contents and meta-contents to the lucene document

                                String name = reader.nextName();
                                indexSingleField(reader,doc, name);

                            } else if (jsonToken == JsonToken.STRING) {
                                reader.nextString();
                            } else if (jsonToken == JsonToken.BOOLEAN) {
                                reader.nextBoolean();
                        }
                        }

                        reader.close();

                    } catch(IOException e){
                        System.out.println(e.getMessage());
                    }

                    writer.addDocument(doc); //index the document

                    datasetsCount++;

                    //commit index after every 50 dataset for efficiency reasons
                    //TODO: tune the parameter
                    if (datasetsCount % 50 == 0)
                        writer.commit();

                    // print progress every 10000 indexed documents, only for debug purpose
                    if (datasetsCount % 10000 == 0) {
                        System.out.printf("%d document(s) (%d files, %d Mbytes) indexed in %d seconds.%n",
                                datasetsCount, filesCount, bytesCount / MBYTE,
                                (System.currentTimeMillis() - start) / 1000);
                    }

                }

                return FileVisitResult.CONTINUE;
            }
        });

        //indexer commit and resource release
        writer.close();

        if (datasetsCount != expectedDatasets) {
            System.out.printf("Expected to index %d documents; %d indexed instead.%n", expectedDatasets, datasetsCount);
        }

        System.out.printf("%d document(s) (%d files, %d Mbytes) indexed in %d seconds.%n", datasetsCount, filesCount,
                bytesCount / MBYTE, (System.currentTimeMillis() - start) / 1000);

        System.out.printf("#### Indexing complete ####%n");
    }

    /**
     * ONLY FOR DEBUGGING PURPOSE
     *
     * @param args command line arguments.
     * @throws Exception if something goes wrong while indexing.
     */
    public static void main(String[] args) throws Exception {

        final int ramBuffer = 256;
        final String indexPath = Constants.indexPath;
        final String datasetDirectoryPath = Constants.datasetsDirectoryPath;

        final int expectedDatasets = 4;
        final String charsetName = "ISO-8859-1";

        CharArraySet cas = AnalyzerUtil.loadStopList("nltk-stopwords.txt");
        final Analyzer a = new StandardAnalyzer(cas);

        //final Similarity sim = new LMDirichletSimilarity(1800);

        DatasetIndexer i = new DatasetIndexer(a, ramBuffer, indexPath, datasetDirectoryPath,
                charsetName, expectedDatasets);

        i.index();

    }

}