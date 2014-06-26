/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.protocol;

import org.bson.codecs.Encoder;
import org.mongodb.BulkWriteResult;
import org.mongodb.MongoException;
import org.mongodb.MongoFuture;
import org.mongodb.MongoNamespace;
import org.mongodb.WriteConcern;
import org.mongodb.connection.Connection;
import org.mongodb.connection.SingleResultCallback;
import org.mongodb.diagnostics.Loggers;
import org.mongodb.diagnostics.logging.Logger;
import org.mongodb.operation.ReplaceRequest;
import org.mongodb.operation.SingleResultFuture;
import org.mongodb.operation.WriteRequest;
import org.mongodb.protocol.message.MessageSettings;
import org.mongodb.protocol.message.ReplaceCommandMessage;

import java.util.List;

import static java.lang.String.format;
import static org.mongodb.assertions.Assertions.notNull;

public class ReplaceCommandProtocol<T> extends WriteCommandProtocol {

    private static final Logger LOGGER = Loggers.getLogger("protocol.replace");

    private final List<ReplaceRequest<T>> replaceRequests;
    private final Encoder<T> encoder;

    public ReplaceCommandProtocol(final MongoNamespace namespace, final boolean ordered, final WriteConcern writeConcern,
                                  final List<ReplaceRequest<T>> replaceRequests, final Encoder<T> encoder) {
        super(namespace, ordered, writeConcern);
        this.replaceRequests = notNull("replaces", replaceRequests);
        this.encoder = notNull("encoder", encoder);
    }

    @Override
    public BulkWriteResult execute(final Connection connection) {
        LOGGER.debug(format("Replacing document in namespace %s on connection [%s] to server %s", getNamespace(), connection.getId(),
                            connection.getServerAddress()));
        BulkWriteResult writeResult = super.execute(connection);
        LOGGER.debug("Replace completed");
        return writeResult;
    }

    @Override
    public MongoFuture<BulkWriteResult> executeAsync(final Connection connection) {
        LOGGER.debug(format("Asynchronously replacing document in namespace %s on connection [%s] to server %s", getNamespace(),
                            connection.getId(), connection.getServerAddress()));
        final SingleResultFuture<BulkWriteResult> future = new SingleResultFuture<BulkWriteResult>();
        super.executeAsync(connection).register(new SingleResultCallback<BulkWriteResult>() {
            @Override
            public void onResult(final BulkWriteResult result, final MongoException e) {
                if (e != null) {
                    LOGGER.debug("Asynchronous replace completed");
                }
                future.init(result, e);
            }
        });
        return future;
    }

    @Override
    protected WriteRequest.Type getType() {
        return WriteRequest.Type.REPLACE;
    }

    @Override
    protected ReplaceCommandMessage<T> createRequestMessage(final MessageSettings messageSettings) {
        return new ReplaceCommandMessage<T>(getNamespace(), isOrdered(), getWriteConcern(), replaceRequests, encoder, messageSettings);
    }

    @Override
    protected org.mongodb.diagnostics.logging.Logger getLogger() {
        return LOGGER;
    }

}