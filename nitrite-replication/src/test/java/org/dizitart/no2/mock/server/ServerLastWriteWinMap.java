/*
 * Copyright (c) 2017-2021 Nitrite author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.dizitart.no2.mock.server;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.DocumentCursor;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.collection.NitriteId;
import org.dizitart.no2.common.streams.BoundedStream;
import org.dizitart.no2.common.tuples.Pair;
import org.dizitart.no2.store.NitriteMap;
import org.dizitart.no2.sync.crdt.DeltaStates;
import org.dizitart.no2.sync.crdt.Tombstone;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.dizitart.no2.collection.FindOptions.skipBy;
import static org.dizitart.no2.common.Constants.*;
import static org.dizitart.no2.filters.Filter.and;
import static org.dizitart.no2.filters.FluentFilter.where;

/**
 * @author Anindya Chatterjee
 */
@Data
@Slf4j
public class ServerLastWriteWinMap {
    private NitriteCollection collection;
    private NitriteMap<NitriteId, Tombstone> tombstoneMap;

    public ServerLastWriteWinMap(NitriteCollection collection, NitriteMap<NitriteId, Tombstone> tombstoneMap) {
        this.collection = collection;
        this.tombstoneMap = tombstoneMap;
    }

    public void merge(DeltaStates snapshot, Long syncTime) {
        if (snapshot.getChangeSet() != null) {
            for (Document entry : snapshot.getChangeSet()) {
                put(entry);
            }
        }

        if (snapshot.getTombstoneMap() != null) {
            for (Map.Entry<String, Long> entry : snapshot.getTombstoneMap().entrySet()) {
                remove(NitriteId.createId(entry.getKey()), entry.getValue(), syncTime);
            }
        }
    }

    public DeltaStates getChangesSince(Long startTime, Long endTime, int offset, int size) {
        DeltaStates state = new DeltaStates();

        // send tombstone info
        BoundedStream<NitriteId, Tombstone> stream
            = new BoundedStream<>((long) offset, (long) size, tombstoneMap.entries());

        for (Pair<NitriteId, Tombstone> entry : stream) {
            Long syncTimestamp = entry.getSecond().getSyncTimestamp();
            if (syncTimestamp > startTime && syncTimestamp <= endTime) {
                state.getTombstoneMap().put(entry.getFirst().getIdValue(),
                    entry.getSecond().getDeleteTimestamp());
            }
        }

        DocumentCursor cursor = collection.find(
            and(
                where("_synced").gt(startTime),
                where("_synced").lte(endTime)
            ), skipBy(offset).limit(size));

        Set<Document> documents = cursor.toSet().stream()
            .peek(document -> document.remove("_synced"))
            .collect(Collectors.toSet());

        state.getChangeSet().addAll(documents);

        return state;
    }

    private void put(Document value) {
        if (value != null) {
            value.put("_synced", System.currentTimeMillis());
            NitriteId key = value.getId();

            Document entry = collection.getById(key);
            if (entry == null) {
                if (tombstoneMap.containsKey(key)) {
                    Long tombstoneTime = tombstoneMap.get(key).getDeleteTimestamp();
                    Long docModifiedTime = value.getLastModifiedSinceEpoch();

                    if (docModifiedTime >= tombstoneTime) {
                        value.put(DOC_SOURCE, REPLICATOR);
                        collection.insert(value);
                        tombstoneMap.remove(key);
                    }
                } else {
                    value.put(DOC_SOURCE, REPLICATOR);
                    collection.insert(value);
                }
            } else {
                Long oldTime = entry.getLastModifiedSinceEpoch();
                Long newTime = value.getLastModifiedSinceEpoch();

                if (newTime > oldTime) {
                    entry.put(DOC_SOURCE, REPLICATOR);
                    collection.remove(entry);

                    value.put(DOC_SOURCE, REPLICATOR);
                    collection.insert(value);
                }
            }
        }
    }

    private void remove(NitriteId key, long timestamp, long syncTime) {
        Document entry = collection.getById(key);
        if (entry != null) {
            entry.put(DOC_SOURCE, REPLICATOR);
            collection.remove(entry);
            Tombstone tombstone = new Tombstone();
            tombstone.setNitriteId(key);
            tombstone.setDeleteTimestamp(timestamp);
            tombstone.setSyncTimestamp(syncTime);
            tombstoneMap.put(key, tombstone);
        } else {
            log.debug("No entry found to remove");
        }
    }
}
