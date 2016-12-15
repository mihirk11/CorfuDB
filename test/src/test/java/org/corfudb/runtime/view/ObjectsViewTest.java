package org.corfudb.runtime.view;

import com.google.common.reflect.TypeToken;
import org.corfudb.protocols.logprotocol.SMREntry;
import org.corfudb.protocols.wireprotocol.LogData;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.collections.SMRMap;
import org.corfudb.runtime.exceptions.TransactionAbortedException;
import org.corfudb.util.serializer.Serializers;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Created by mwei on 2/18/16.
 */
public class ObjectsViewTest extends AbstractViewTest {

    public static boolean referenceTX(Map<String, String> smrMap) {
        smrMap.put("a", "b");
        assertThat(smrMap)
                .containsEntry("a", "b");
        return true;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void canCopyObject()
            throws Exception {
        //begin tests
        CorfuRuntime r = getDefaultRuntime();

        Map<String, String> smrMap = r.getObjectsView().open("map a", SMRMap.class);
        smrMap.put("a", "a");
        Map<String, String> smrMapCopy = r.getObjectsView().copy(smrMap, "map a copy");
        smrMapCopy.put("b", "b");

        assertThat(smrMapCopy)
                .containsEntry("a", "a")
                .containsEntry("b", "b");

        assertThat(smrMap)
                .containsEntry("a", "a")
                .doesNotContainEntry("b", "b");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void cannotCopyNonCorfuObject()
            throws Exception {
        //begin tests
        CorfuRuntime r = getDefaultRuntime();

        assertThatThrownBy(() -> {
            r.getObjectsView().copy(new HashMap<String, String>(), CorfuRuntime.getStreamID("test"));
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void canAbortNoTransaction()
            throws Exception {
        //begin tests
        CorfuRuntime r = getDefaultRuntime();
        r.getObjectsView().TXAbort();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void abortedTransactionDoesNotConflict()
            throws Exception {
        //Enbale transaction logging
        CorfuRuntime r = getDefaultRuntime()
                .setTransactionLogging(true);

        SMRMap<String, String> map = getDefaultRuntime().getObjectsView()
                .build()
                .setStreamName("map a")
                .setTypeToken(new TypeToken<SMRMap<String, String>>() {})
                .open();

        // TODO: fix so this does not require mapCopy.
        SMRMap<String, String> mapCopy = getDefaultRuntime().getObjectsView()
                .build()
                .setStreamName("map a")
                .setTypeToken(new TypeToken<SMRMap<String, String>>() {})
                .addOption(ObjectOpenOptions.NO_CACHE)
                .open();


        map.put("initial", "value");

        Semaphore s1 = new Semaphore(0);
        Semaphore s2 = new Semaphore(0);

        // Schedule two threads, the first starts a transaction and reads,
        // then waits for the second thread to finish.
        // the second starts a transaction, waits for the first tx to read
        // and commits.
        // The first thread then resumes and attempts to commit. It should abort.
        scheduleConcurrently(1, t -> {
            assertThatThrownBy(() -> {
                getRuntime().getObjectsView().TXBegin();
                map.get("k");
                s1.release();   // Let thread 2 start.
                s2.acquire();   // Wait for thread 2 to commit.
                map.put("k", "v1");
                getRuntime().getObjectsView().TXEnd();
            }).isInstanceOf(TransactionAbortedException.class);
        });

        scheduleConcurrently(1, t -> {
            s1.acquire();   // Wait for thread 1 to read
            getRuntime().getObjectsView().TXBegin();
            mapCopy.put("k", "v2");
            getRuntime().getObjectsView().TXEnd();
            s2.release();
        });

        executeScheduled(2, PARAMETERS.TIMEOUT_LONG);

        // The result should contain T2s modification.
        assertThat(map)
                .containsEntry("k", "v2");

        //TODO: currently the txn stream is broken should figure out what to do about it.
        // The transaction stream should have two transaction entries, one for the first
        // failed transaction and the other for successful transaction
        /*
        StreamView txStream = r.getStreamsView().get(ObjectsView.TRANSACTION_STREAM_ID);
        LogData[] txns = txStream.readTo(Long.MAX_VALUE);
        assertThat(txns.length).isEqualTo(1);
        assertThat(txns[0].getLogEntry(getRuntime()).getType()).isEqualTo(LogEntry.LogEntryType.TX);
        TXEntry tx1 = (TXEntry)txns[0].getLogEntry(getRuntime());
        assertThat(tx1.isAborted()).isEqualTo(false);
        */
    }

    @Test
    @SuppressWarnings("unchecked")
    public void unrelatedStreamDoesNotConflict()
            throws Exception {
        //begin tests
        CorfuRuntime r = getDefaultRuntime();

        Map<String, String> smrMap = r.getObjectsView().open("map a", SMRMap.class);
        StreamView streamB = r.getStreamsView().get(CorfuRuntime.getStreamID("b"));
        smrMap.put("a", "b");
        streamB.write(new SMREntry("hi", new Object[]{"hello"}, Serializers.PRIMITIVE));

        //this TX should not conflict
        assertThat(smrMap)
                .doesNotContainKey("b");
        r.getObjectsView().TXBegin();
        String b = smrMap.get("a");
        smrMap.put("b", b);
        r.getObjectsView().TXEnd();

        assertThat(smrMap)
                .containsEntry("b", "b");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void unrelatedTransactionDoesNotConflict()
            throws Exception {
        //begin tests
        CorfuRuntime r = getDefaultRuntime();

        Map<String, String> smrMap = r.getObjectsView().open("map a", SMRMap.class);
        Map<String, String> smrMapB = r.getObjectsView().open("map b", SMRMap.class);

        smrMap.put("a", "b");

        r.getObjectsView().TXBegin();
        String b = smrMap.get("a");
        smrMapB.put("b", b);
        r.getObjectsView().TXEnd();

        //this TX should not conflict
        assertThat(smrMap)
                .doesNotContainKey("b");
        r.getObjectsView().TXBegin();
        b = smrMap.get("a");
        smrMap.put("b", b);
        r.getObjectsView().TXEnd();

        assertThat(smrMap)
                .containsEntry("b", "b");
    }

}
