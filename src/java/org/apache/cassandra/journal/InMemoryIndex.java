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
package org.apache.cassandra.journal;

import java.io.IOException;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileOutputStreamPlus;

/**
 * An index for a segment that's still being updated by journal writers concurrently.
 */
final class InMemoryIndex<K> extends Index<K>
{
    private static final long[] EMPTY = new long[0];

    private final NavigableMap<K, long[]> index;

    // CSLM#lastKey() can be costly, so track lastId separately;
    // TODO: this could easily be premature and misguided;
    //       benchmark to ensure it's not acitevly harmful
    private final AtomicReference<K> lastId;

    static <K> InMemoryIndex<K> create(KeySupport<K> keySupport)
    {
        return new InMemoryIndex<>(keySupport, new ConcurrentSkipListMap<>(keySupport));
    }

    private InMemoryIndex(KeySupport<K> keySupport, NavigableMap<K, long[]> index)
    {
        super(keySupport);
        this.index = index;
        this.lastId = new AtomicReference<>();
    }

    public void update(K id, int offset, int size)
    {
        long currentOffsetAndSize = composeOffsetAndSize(offset, size);
        index.merge(id, new long[] { currentOffsetAndSize },
                    (current, value) ->
                    {
                        int idx = Arrays.binarySearch(current, currentOffsetAndSize);
                        if (idx >= 0) // repeat update() call; shouldn't occur, but we might as well allow this NOOP
                            return current;

                        /* Merge the new offset with existing values */
                        int pos = -idx - 1;
                        long[] merged = new long[current.length + 1];
                        System.arraycopy(current, 0, merged, 0, pos);
                        merged[pos] = currentOffsetAndSize;
                        System.arraycopy(current, pos, merged, pos + 1, current.length - pos);
                        return merged;
                    });

        lastId.accumulateAndGet(id, (current, update) -> (null == current || keySupport.compare(current, update) < 0) ? update : current);
    }

    @Override
    @Nullable
    public K firstId()
    {
        return index.isEmpty() ? null : index.firstKey();
    }

    @Override
    @Nullable
    public K lastId()
    {
        return lastId.get();
    }

    @Override
    public long[] lookUp(K id)
    {
        return mayContainId(id) ? index.getOrDefault(id, EMPTY) : EMPTY;
    }

    @Override
    public long lookUpFirst(K id)
    {
        long[] offsets = lookUp(id);
        return offsets.length == 0 ? -1 : offsets[0];
    }

    @Override
    long[] lookUpAll(K id)
    {
        return lookUp(id);
    }

    public void persist(Descriptor descriptor)
    {
        File tmpFile = descriptor.tmpFileFor(Component.INDEX);
        try (FileOutputStreamPlus out = new FileOutputStreamPlus(tmpFile))
        {
            OnDiskIndex.write(index, keySupport, out, descriptor.userVersion);

            out.flush();
            out.sync();
        }
        catch (IOException e)
        {
            throw new JournalWriteError(descriptor, tmpFile, e);
        }
        tmpFile.move(descriptor.fileFor(Component.INDEX));
    }

    static <K> InMemoryIndex<K> rebuild(Descriptor descriptor, KeySupport<K> keySupport, int fsyncedLimit)
    {
        InMemoryIndex<K> index = new InMemoryIndex<>(keySupport, new TreeMap<>(keySupport));

        try (StaticSegment.SequentialReader<K> reader = StaticSegment.reader(descriptor, keySupport, fsyncedLimit))
        {
            int last = -1;
            while (reader.advance())
            {
                int current = reader.offset();
                if (last >= 0)
                    index.update(reader.id(), last, current);
                last = current;
            }

        }
        return index;
    }

    @Override
    public void close()
    {
    }
}
