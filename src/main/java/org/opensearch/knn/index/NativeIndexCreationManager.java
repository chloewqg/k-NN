/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.knn.index;

import lombok.extern.log4j.Log4j2;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.search.DocIdSetIterator;
import org.opensearch.knn.index.codec.KNN80Codec.KNN80DocValuesConsumer;
import org.opensearch.knn.index.codec.util.KNNCodecUtil;
import org.opensearch.knn.index.codec.util.SerializationMode;
import org.opensearch.knn.index.vectorvalues.KNNVectorValues;
import org.opensearch.knn.index.vectorvalues.KNNVectorValuesIterator;
import org.opensearch.knn.jni.JNICommons;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This is a single layer that will be responsible for creating the native indices. Right now this is just a POC code,
 * this needs to be fixed. Its more of a testing to see if everything works correctly.
 */
@Log4j2
public class NativeIndexCreationManager {

    public static void startIndexCreation(
        final SegmentWriteState segmentWriteState,
        final KNNVectorValues<float[]> vectorValues,
        final FieldInfo fieldInfo
    ) throws IOException {
        KNNCodecUtil.Pair pair = streamFloatVectors(vectorValues);
        if (pair.getVectorAddress() == 0 || pair.docs.length == 0) {
            log.info("Skipping engine index creation as there are no vectors or docs in the segment");
            return;
        }
        createNativeIndex(segmentWriteState, fieldInfo, pair);
    }

    private static void createNativeIndex(
        final SegmentWriteState segmentWriteState,
        final FieldInfo fieldInfo,
        final KNNCodecUtil.Pair pair
    ) throws IOException {
        KNN80DocValuesConsumer.createNativeIndex(segmentWriteState, fieldInfo, pair);
    }

    private static KNNCodecUtil.Pair streamFloatVectors(final KNNVectorValues<float[]> kNNVectorValues) throws IOException {
        List<float[]> vectorList = new ArrayList<>();
        List<Integer> docIdList = new ArrayList<>();
        long vectorAddress = 0;
        int dimension = 0;
        long totalLiveDocs = kNNVectorValues.totalLiveDocs();
        long vectorsStreamingMemoryLimit = KNNSettings.getVectorStreamingMemoryLimit().getBytes();
        long vectorsPerTransfer = Integer.MIN_VALUE;

        KNNVectorValuesIterator iterator = kNNVectorValues.getVectorValuesIterator();

        for (int doc = iterator.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = iterator.nextDoc()) {
            float[] temp = kNNVectorValues.getVector();
            // This temp object and copy of temp object is required because when we map floats we read to a memory
            // location in heap always for floatVectorValues. Ref: OffHeapFloatVectorValues.vectorValue.
            float[] vector = Arrays.copyOf(temp, temp.length);
            dimension = vector.length;
            if (vectorsPerTransfer == Integer.MIN_VALUE) {
                vectorsPerTransfer = (dimension * Float.BYTES * totalLiveDocs) / vectorsStreamingMemoryLimit;
                // This condition comes if vectorsStreamingMemoryLimit is higher than total number floats to transfer
                // Doing this will reduce 1 extra trip to JNI layer.
                if (vectorsPerTransfer == 0) {
                    vectorsPerTransfer = totalLiveDocs;
                }
            }

            if (vectorList.size() == vectorsPerTransfer) {
                vectorAddress = JNICommons.storeVectorData(vectorAddress, vectorList.toArray(new float[][] {}), totalLiveDocs * dimension);
                // We should probably come up with a better way to reuse the vectorList memory which we have
                // created. Problem here is doing like this can lead to a lot of list memory which is of no use and
                // will be garbage collected later on, but it creates pressure on JVM. We should revisit this.
                vectorList = new ArrayList<>();
            }

            vectorList.add(vector);
            docIdList.add(doc);
        }

        if (vectorList.isEmpty() == false) {
            vectorAddress = JNICommons.storeVectorData(vectorAddress, vectorList.toArray(new float[][] {}), totalLiveDocs * dimension);
        }
        // SerializationMode.COLLECTION_OF_FLOATS is not getting used. I just added it to ensure code successfully
        // works.
        return new KNNCodecUtil.Pair(
            docIdList.stream().mapToInt(Integer::intValue).toArray(),
            vectorAddress,
            dimension,
            SerializationMode.COLLECTION_OF_FLOATS
        );
    }
}
