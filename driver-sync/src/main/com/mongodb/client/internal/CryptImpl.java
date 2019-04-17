/*
 * Copyright 2008-present MongoDB, Inc.
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

package com.mongodb.client.internal;

import com.mongodb.AutoEncryptOptions;
import com.mongodb.ClientSideEncryptionOptions;
import com.mongodb.MongoException;
import com.mongodb.MongoInternalException;
import com.mongodb.MongoNamespace;
import com.mongodb.crypt.capi.MongoCrypt;
import com.mongodb.crypt.capi.MongoCryptContext;
import com.mongodb.crypt.capi.MongoKeyDecryptor;
import org.bson.BsonBinaryReader;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;

import static com.mongodb.assertions.Assertions.isTrue;
import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.crypt.capi.MongoCryptContext.State;

class CryptImpl implements Crypt {

    private final ClientSideEncryptionOptions options;
    private final MongoCrypt mongoCrypt;
    private final CollectionInfoRetriever collectionInfoRetriever;
    private final CommandMarker commandMarker;
    private final KeyVault keyVault;
    private final KeyManagementService keyManagementService;

    CryptImpl(final ClientSideEncryptionOptions options, final MongoCrypt mongoCrypt,
              final CollectionInfoRetriever collectionInfoRetriever, final CommandMarker commandMarker,
              final KeyVault keyVault, final KeyManagementService keyManagementService) {
        this.options = options;
        this.mongoCrypt = mongoCrypt;
        this.collectionInfoRetriever = collectionInfoRetriever;
        this.commandMarker = commandMarker;
        this.keyVault = keyVault;
        this.keyManagementService = keyManagementService;
    }

    @Override
    public boolean isEnabled(final MongoNamespace namespace) {
        AutoEncryptOptions autoEncryptOptions = options.getNamespaceToAutoEncryptOptionsMap().get(namespace.getFullName());
        return autoEncryptOptions != null && autoEncryptOptions.isEnabled();
    }

    @Override
    public RawBsonDocument encrypt(final MongoNamespace namespace, final RawBsonDocument command) {
        notNull("databaseName", namespace);
        notNull("command", command);
        isTrue("encryption enabled", isEnabled(namespace));

        AutoEncryptOptions autoEncryptOptions = options.getNamespaceToAutoEncryptOptionsMap().get(namespace.getFullName());

        MongoCryptContext encryptionContext = mongoCrypt.createEncryptionContext(namespace.getFullName(),
                autoEncryptOptions.getLocalSchemaDocument());

        try {
            RawBsonDocument encryptedDocument = executeStateMachine(encryptionContext, namespace.getDatabaseName(), command);

            // TODO: hopefully remove this
            BsonDocument clonedDocument = new BsonDocumentCodec()
                    .decode(new BsonBinaryReader(encryptedDocument.getByteBuffer().asNIO()), DecoderContext.builder().build());
            clonedDocument.remove("$readPreference");
            clonedDocument.remove("$db");

            return new RawBsonDocument(clonedDocument, new BsonDocumentCodec());
        } finally {
            encryptionContext.close();
        }
    }

    @Override
    public RawBsonDocument decrypt(final MongoNamespace namespace, final RawBsonDocument commandResponse) {
        notNull("commandResponse", commandResponse);
        isTrue("decryption enabled", isEnabled(namespace));

        MongoCryptContext decryptionContext = mongoCrypt.createDecryptionContext(commandResponse);

        try {
            return executeStateMachine(decryptionContext, null, commandResponse);
        } finally {
            decryptionContext.close();
        }
    }

    private RawBsonDocument executeStateMachine(final MongoCryptContext cryptContext, final String databaseName,
                                                final RawBsonDocument defaultResponse) {
        while (true) {
            State state = cryptContext.getState();
            switch (state) {
                case NEED_MONGO_COLLINFO:
                    BsonDocument collectionInfo = collectionInfoRetriever.filter(databaseName, cryptContext.getMongoOperation());
                    if (collectionInfo != null) {
                        cryptContext.addMongoOperationResult(collectionInfo);
                    }
                    cryptContext.completeMongoOperation();
                    break;
                case NEED_MONGO_MARKINGS:
                    BsonDocument markedCommand = commandMarker.mark(databaseName, cryptContext.getMongoOperation(), defaultResponse);
                    cryptContext.addMongoOperationResult(markedCommand);
                    cryptContext.completeMongoOperation();
                    break;
                case NEED_MONGO_KEYS:
                    fetchKeys(cryptContext);
                    break;
                case NEED_KMS:
                    decryptKeys(cryptContext);
                    break;
                case READY:
                    return (RawBsonDocument) cryptContext.finish();
                case NOTHING_TO_DO:
                    return defaultResponse;
                case DONE:
                    // TODO: nothing to do here?
                    break;
                default:
                    throw new MongoInternalException("Unsupported encryptor state + " + state);
            }
        }
    }


    @Override
    public void close() {
        mongoCrypt.close();
    }

    private void fetchKeys(final MongoCryptContext keyBroker) {
        Iterator<BsonDocument> iterator = keyVault.find(keyBroker.getMongoOperation());
        while (iterator.hasNext()) {
            keyBroker.addMongoOperationResult(iterator.next());
        }
        keyBroker.completeMongoOperation();
    }

    private void decryptKeys(final MongoCryptContext cryptContext) {
        MongoKeyDecryptor keyDecryptor = cryptContext.nextKeyDecryptor();
        while (keyDecryptor != null) {
            decryptKey(keyDecryptor);
            keyDecryptor = cryptContext.nextKeyDecryptor();
        }
        cryptContext.completeKeyDecryptors();
    }

    private void decryptKey(final MongoKeyDecryptor keyDecryptor) {
        InputStream inputStream = keyManagementService.stream(keyDecryptor.getHostName(), keyDecryptor.getMessage());
        try {
            byte[] bytes = new byte[4096];

            int bytesNeeded = keyDecryptor.bytesNeeded();

            while (bytesNeeded > 0) {
                int bytesRead = inputStream.read(bytes, 0, bytesNeeded);
                keyDecryptor.feed(ByteBuffer.wrap(bytes, 0, bytesRead));
                bytesNeeded = keyDecryptor.bytesNeeded();
            }
        } catch (IOException e) {
            throw new MongoException("Exception decrypting key", e);  // TODO: change exception type
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private MongoNamespace getNamespace(final String databaseName, final RawBsonDocument command) {
        // TODO: aggregate command sometimes doesn't have a collection as the value of the first key, e.g. for $currentOp
        // What to do about that?
        return new MongoNamespace(databaseName, command.getString(command.getFirstKey()).getValue());
    }
}