/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security;

import com.android.org.conscrypt.OpenSSLEngine;
import com.android.org.conscrypt.OpenSSLKeyHolder;

import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore.Entry;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.ProtectionParameter;
import java.security.KeyStore;
import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.crypto.SecretKey;

/**
 * A java.security.KeyStore interface for the Android KeyStore. An instance of
 * it can be created via the {@link java.security.KeyStore#getInstance(String)
 * KeyStore.getInstance("AndroidKeyStore")} interface. This returns a
 * java.security.KeyStore backed by this "AndroidKeyStore" implementation.
 * <p>
 * This is built on top of Android's keystore daemon. The convention of alias
 * use is:
 * <p>
 * PrivateKeyEntry will have a Credentials.USER_PRIVATE_KEY as the private key,
 * Credentials.USER_CERTIFICATE as the first certificate in the chain (the one
 * that corresponds to the private key), and then a Credentials.CA_CERTIFICATE
 * entry which will have the rest of the chain concatenated in BER format.
 * <p>
 * TrustedCertificateEntry will just have a Credentials.CA_CERTIFICATE entry
 * with a single certificate.
 *
 * @hide
 */
public class AndroidKeyStore extends KeyStoreSpi {
    public static final String NAME = "AndroidKeyStore";

    private android.security.KeyStore mKeyStore;

    @Override
    public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException,
            UnrecoverableKeyException {
        if (isPrivateKeyEntry(alias)) {
            final OpenSSLEngine engine = OpenSSLEngine.getInstance("keystore");
            try {
                return engine.getPrivateKeyById(Credentials.USER_PRIVATE_KEY + alias);
            } catch (InvalidKeyException e) {
                UnrecoverableKeyException t = new UnrecoverableKeyException("Can't get key");
                t.initCause(e);
                throw t;
            }
        } else if (isSecretKeyEntry(alias)) {
            KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
            String keyAliasInKeystore = Credentials.USER_SECRET_KEY + alias;
            int errorCode = mKeyStore.getKeyCharacteristics(
                    keyAliasInKeystore, null, null, keyCharacteristics);
            if ((errorCode != KeymasterDefs.KM_ERROR_OK)
                    && (errorCode != android.security.KeyStore.NO_ERROR)) {
                throw new UnrecoverableKeyException("Failed to load information about key."
                        + " Error code: " + errorCode);
            }

            int keymasterAlgorithm =
                    keyCharacteristics.hwEnforced.getInt(KeymasterDefs.KM_TAG_ALGORITHM, -1);
            if (keymasterAlgorithm == -1) {
                keymasterAlgorithm =
                        keyCharacteristics.swEnforced.getInt(KeymasterDefs.KM_TAG_ALGORITHM, -1);
            }
            if (keymasterAlgorithm == -1) {
                throw new UnrecoverableKeyException("Key algorithm unknown");
            }
            @KeyStoreKeyConstraints.AlgorithmEnum int keyAlgorithm;
            try {
                keyAlgorithm = KeyStoreKeyConstraints.Algorithm.fromKeymaster(keymasterAlgorithm);
            } catch (IllegalArgumentException e) {
                throw (UnrecoverableKeyException)
                        new UnrecoverableKeyException("Unsupported key algorithm").initCause(e);
            }

            int keymasterDigest =
                    keyCharacteristics.hwEnforced.getInt(KeymasterDefs.KM_TAG_DIGEST, -1);
            if (keymasterDigest == -1) {
                keymasterDigest =
                        keyCharacteristics.swEnforced.getInt(KeymasterDefs.KM_TAG_DIGEST, -1);
            }
            @KeyStoreKeyConstraints.DigestEnum Integer digest = null;
            if (keymasterDigest != -1) {
                try {
                    digest = KeyStoreKeyConstraints.Digest.fromKeymaster(keymasterDigest);
                } catch (IllegalArgumentException e) {
                    throw (UnrecoverableKeyException)
                            new UnrecoverableKeyException("Unsupported digest").initCause(e);
                }
            }

            String keyAlgorithmString;
            try {
                keyAlgorithmString = KeyStoreKeyConstraints.Algorithm.toJCASecretKeyAlgorithm(
                    keyAlgorithm, digest);
            } catch (IllegalArgumentException e) {
                throw (UnrecoverableKeyException)
                        new UnrecoverableKeyException("Unsupported secret key type").initCause(e);
            }

            return new KeyStoreSecretKey(keyAliasInKeystore, keyAlgorithmString);
        }

        return null;
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        final X509Certificate leaf = (X509Certificate) engineGetCertificate(alias);
        if (leaf == null) {
            return null;
        }

        final Certificate[] caList;

        final byte[] caBytes = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        if (caBytes != null) {
            final Collection<X509Certificate> caChain = toCertificates(caBytes);

            caList = new Certificate[caChain.size() + 1];

            final Iterator<X509Certificate> it = caChain.iterator();
            int i = 1;
            while (it.hasNext()) {
                caList[i++] = it.next();
            }
        } else {
            caList = new Certificate[1];
        }

        caList[0] = leaf;

        return caList;
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        byte[] certificate = mKeyStore.get(Credentials.USER_CERTIFICATE + alias);
        if (certificate != null) {
            return toCertificate(certificate);
        }

        certificate = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        if (certificate != null) {
            return toCertificate(certificate);
        }

        return null;
    }

    private static X509Certificate toCertificate(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) certFactory
                    .generateCertificate(new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificate in keystore", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<X509Certificate> toCertificates(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (Collection<X509Certificate>) certFactory
                    .generateCertificates(new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificates in keystore", e);
            return new ArrayList<X509Certificate>();
        }
    }

    private Date getModificationDate(String alias) {
        final long epochMillis = mKeyStore.getmtime(alias);
        if (epochMillis == -1L) {
            return null;
        }

        return new Date(epochMillis);
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        Date d = getModificationDate(Credentials.USER_PRIVATE_KEY + alias);
        if (d != null) {
            return d;
        }

        d = getModificationDate(Credentials.USER_SECRET_KEY + alias);
        if (d != null) {
            return d;
        }

        d = getModificationDate(Credentials.USER_CERTIFICATE + alias);
        if (d != null) {
            return d;
        }

        return getModificationDate(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
            throws KeyStoreException {
        if ((password != null) && (password.length > 0)) {
            throw new KeyStoreException("entries cannot be protected with passwords");
        }

        if (key instanceof PrivateKey) {
            setPrivateKeyEntry(alias, (PrivateKey) key, chain, null);
        } else if (key instanceof SecretKey) {
            setSecretKeyEntry(alias, (SecretKey) key, null);
        } else {
            throw new KeyStoreException("Only PrivateKey and SecretKey are supported");
        }
    }

    private void setPrivateKeyEntry(String alias, PrivateKey key, Certificate[] chain,
            KeyStoreParameter params) throws KeyStoreException {
        byte[] keyBytes = null;

        final String pkeyAlias;
        if (key instanceof OpenSSLKeyHolder) {
            pkeyAlias = ((OpenSSLKeyHolder) key).getOpenSSLKey().getAlias();
        } else {
            pkeyAlias = null;
        }

        final boolean shouldReplacePrivateKey;
        if (pkeyAlias != null && pkeyAlias.startsWith(Credentials.USER_PRIVATE_KEY)) {
            final String keySubalias = pkeyAlias.substring(Credentials.USER_PRIVATE_KEY.length());
            if (!alias.equals(keySubalias)) {
                throw new KeyStoreException("Can only replace keys with same alias: " + alias
                        + " != " + keySubalias);
            }

            shouldReplacePrivateKey = false;
        } else {
            // Make sure the PrivateKey format is the one we support.
            final String keyFormat = key.getFormat();
            if ((keyFormat == null) || (!"PKCS#8".equals(keyFormat))) {
                throw new KeyStoreException(
                        "Only PrivateKeys that can be encoded into PKCS#8 are supported");
            }

            // Make sure we can actually encode the key.
            keyBytes = key.getEncoded();
            if (keyBytes == null) {
                throw new KeyStoreException("PrivateKey has no encoding");
            }

            shouldReplacePrivateKey = true;
        }

        // Make sure the chain exists since this is a PrivateKey
        if ((chain == null) || (chain.length == 0)) {
            throw new KeyStoreException("Must supply at least one Certificate with PrivateKey");
        }

        // Do chain type checking.
        X509Certificate[] x509chain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            if (!"X.509".equals(chain[i].getType())) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            if (!(chain[i] instanceof X509Certificate)) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            x509chain[i] = (X509Certificate) chain[i];
        }

        final byte[] userCertBytes;
        try {
            userCertBytes = x509chain[0].getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException("Couldn't encode certificate #1", e);
        }

        /*
         * If we have a chain, store it in the CA certificate slot for this
         * alias as concatenated DER-encoded certificates. These can be
         * deserialized by {@link CertificateFactory#generateCertificates}.
         */
        final byte[] chainBytes;
        if (chain.length > 1) {
            /*
             * The chain is passed in as {user_cert, ca_cert_1, ca_cert_2, ...}
             * so we only need the certificates starting at index 1.
             */
            final byte[][] certsBytes = new byte[x509chain.length - 1][];
            int totalCertLength = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                try {
                    certsBytes[i] = x509chain[i + 1].getEncoded();
                    totalCertLength += certsBytes[i].length;
                } catch (CertificateEncodingException e) {
                    throw new KeyStoreException("Can't encode Certificate #" + i, e);
                }
            }

            /*
             * Serialize this into one byte array so we can later call
             * CertificateFactory#generateCertificates to recover them.
             */
            chainBytes = new byte[totalCertLength];
            int outputOffset = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                final int certLength = certsBytes[i].length;
                System.arraycopy(certsBytes[i], 0, chainBytes, outputOffset, certLength);
                outputOffset += certLength;
                certsBytes[i] = null;
            }
        } else {
            chainBytes = null;
        }

        /*
         * Make sure we clear out all the appropriate types before trying to
         * write.
         */
        if (shouldReplacePrivateKey) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
        } else {
            Credentials.deleteCertificateTypesForAlias(mKeyStore, alias);
            Credentials.deleteSecretKeyTypeForAlias(mKeyStore, alias);
        }

        final int flags = (params == null) ? 0 : params.getFlags();

        if (shouldReplacePrivateKey
                && !mKeyStore.importKey(Credentials.USER_PRIVATE_KEY + alias, keyBytes,
                        android.security.KeyStore.UID_SELF, flags)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new KeyStoreException("Couldn't put private key in keystore");
        } else if (!mKeyStore.put(Credentials.USER_CERTIFICATE + alias, userCertBytes,
                android.security.KeyStore.UID_SELF, flags)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new KeyStoreException("Couldn't put certificate #1 in keystore");
        } else if (chainBytes != null
                && !mKeyStore.put(Credentials.CA_CERTIFICATE + alias, chainBytes,
                        android.security.KeyStore.UID_SELF, flags)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new KeyStoreException("Couldn't put certificate chain in keystore");
        }
    }

    private void setSecretKeyEntry(String entryAlias, SecretKey key, KeyStoreParameter params)
            throws KeyStoreException {
        if (key instanceof KeyStoreSecretKey) {
            // KeyStore-backed secret key. It cannot be duplicated into another entry and cannot
            // overwrite its own entry.
            String keyAliasInKeystore = ((KeyStoreSecretKey) key).getAlias();
            if (keyAliasInKeystore == null) {
                throw new KeyStoreException("KeyStore-backed secret key does not have an alias");
            }
            if (!keyAliasInKeystore.startsWith(Credentials.USER_SECRET_KEY)) {
                throw new KeyStoreException("KeyStore-backed secret key has invalid alias: "
                        + keyAliasInKeystore);
            }
            String keyEntryAlias =
                    keyAliasInKeystore.substring(Credentials.USER_SECRET_KEY.length());
            if (!entryAlias.equals(keyEntryAlias)) {
                throw new KeyStoreException("Can only replace KeyStore-backed keys with same"
                        + " alias: " + entryAlias + " != " + keyEntryAlias);
            }
            // This is the entry where this key is already stored. No need to do anything.
            if (params != null) {
                throw new KeyStoreException("Modifying KeyStore-backed key using protection"
                        + " parameters not supported");
            }
            return;
        }

        if (params == null) {
            throw new KeyStoreException(
                    "Protection parameters must be specified when importing a symmetric key");
        }

        // Not a KeyStore-backed secret key -- import its key material into keystore.
        String keyExportFormat = key.getFormat();
        if (keyExportFormat == null) {
            throw new KeyStoreException(
                    "Only secret keys that export their key material are supported");
        } else if (!"RAW".equals(keyExportFormat)) {
            throw new KeyStoreException(
                    "Unsupported secret key material export format: " + keyExportFormat);
        }
        byte[] keyMaterial = key.getEncoded();
        if (keyMaterial == null) {
            throw new KeyStoreException("Key did not export its key material despite supporting"
                    + " RAW format export");
        }

        String keyAlgorithmString = key.getAlgorithm();
        @KeyStoreKeyConstraints.AlgorithmEnum int keyAlgorithm;
        @KeyStoreKeyConstraints.DigestEnum Integer digest;
        try {
            keyAlgorithm =
                    KeyStoreKeyConstraints.Algorithm.fromJCASecretKeyAlgorithm(keyAlgorithmString);
            digest = KeyStoreKeyConstraints.Digest.fromJCASecretKeyAlgorithm(keyAlgorithmString);
        } catch (IllegalArgumentException e) {
            throw new KeyStoreException("Unsupported secret key algorithm: " + keyAlgorithmString);
        }

        if ((params.getAlgorithm() != null) && (params.getAlgorithm() != keyAlgorithm)) {
            throw new KeyStoreException("Key algorithm mismatch. Key: " + keyAlgorithmString
                    + ", parameter spec: "
                    + KeyStoreKeyConstraints.Algorithm.toString(params.getAlgorithm()));
        }

        KeymasterArguments args = new KeymasterArguments();
        args.addInt(KeymasterDefs.KM_TAG_ALGORITHM,
                KeyStoreKeyConstraints.Algorithm.toKeymaster(keyAlgorithm));

        if (digest != null) {
            // Digest available from JCA key algorithm
            if (params.getDigest() != null) {
                // Digest also specified in parameters -- check that these two match
                if (digest != params.getDigest()) {
                    throw new KeyStoreException("Key digest mismatch. Key: " + keyAlgorithmString
                            + ", parameter spec: "
                            + KeyStoreKeyConstraints.Digest.toString(params.getDigest()));
                }
            }
        } else {
            // Digest not available from JCA key algorithm
            digest = params.getDigest();
        }
        if (digest != null) {
            args.addInt(KeymasterDefs.KM_TAG_DIGEST,
                    KeyStoreKeyConstraints.Digest.toKeymaster(digest));
            Integer digestOutputSizeBytes =
                    KeyStoreKeyConstraints.Digest.getOutputSizeBytes(digest);
            if (digestOutputSizeBytes != null) {
                // TODO: Remove MAC length constraint once Keymaster API no longer requires it.
                // TODO: Switch to bits instead of bytes, once this is fixed in Keymaster
                args.addInt(KeymasterDefs.KM_TAG_MAC_LENGTH, digestOutputSizeBytes);
            }
        }
        if (keyAlgorithm == KeyStoreKeyConstraints.Algorithm.HMAC) {
            if (digest == null) {
                throw new IllegalStateException("Digest algorithm must be specified for key"
                        + " algorithm " + keyAlgorithmString);
            }
        }

        @KeyStoreKeyConstraints.PurposeEnum int purposes = (params.getPurposes() != null)
                ? params.getPurposes()
                : (KeyStoreKeyConstraints.Purpose.ENCRYPT
                        | KeyStoreKeyConstraints.Purpose.DECRYPT
                        | KeyStoreKeyConstraints.Purpose.SIGN
                        | KeyStoreKeyConstraints.Purpose.VERIFY);
        for (int keymasterPurpose :
            KeyStoreKeyConstraints.Purpose.allToKeymaster(purposes)) {
            args.addInt(KeymasterDefs.KM_TAG_PURPOSE, keymasterPurpose);
        }
        if (params.getBlockMode() != null) {
            args.addInt(KeymasterDefs.KM_TAG_BLOCK_MODE,
                    KeyStoreKeyConstraints.BlockMode.toKeymaster(params.getBlockMode()));
        }
        if (params.getPadding() != null) {
            args.addInt(KeymasterDefs.KM_TAG_PADDING,
                    KeyStoreKeyConstraints.Padding.toKeymaster(params.getPadding()));
        }
        if (params.getMaxUsesPerBoot() != null) {
            args.addInt(KeymasterDefs.KM_TAG_MAX_USES_PER_BOOT, params.getMaxUsesPerBoot());
        }
        if (params.getMinSecondsBetweenOperations() != null) {
            args.addInt(KeymasterDefs.KM_TAG_MIN_SECONDS_BETWEEN_OPS,
                    params.getMinSecondsBetweenOperations());
        }
        if (params.getUserAuthenticators().isEmpty()) {
            args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        } else {
        // TODO: Pass-in user authenticator IDs once the Keymaster API has stabilized
//            for (int userAuthenticatorId : params.getUserAuthenticators()) {
//                args.addInt(KeymasterDefs.KM_TAG_USER_AUTH_ID, userAuthenticatorId);
//            }
        }
        if (params.getUserAuthenticationValidityDurationSeconds() != null) {
            args.addInt(KeymasterDefs.KM_TAG_AUTH_TIMEOUT,
                    params.getUserAuthenticationValidityDurationSeconds());
        }
        if (params.getKeyValidityStart() != null) {
            args.addDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME, params.getKeyValidityStart());
        }
        if (params.getKeyValidityForOriginationEnd() != null) {
            args.addDate(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                    params.getKeyValidityForOriginationEnd());
        }
        if (params.getKeyValidityForConsumptionEnd() != null) {
            args.addDate(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                    params.getKeyValidityForConsumptionEnd());
        }

        // TODO: Remove this once keymaster does not require us to specify the size of imported key.
        args.addInt(KeymasterDefs.KM_TAG_KEY_SIZE, keyMaterial.length * 8);

        if (((purposes & KeyStoreKeyConstraints.Purpose.ENCRYPT) != 0)
                || ((purposes & KeyStoreKeyConstraints.Purpose.DECRYPT) != 0)) {
            // Permit caller-specified IV. This is needed for the Cipher abstraction.
            args.addBoolean(KeymasterDefs.KM_TAG_CALLER_NONCE);
        }

        Credentials.deleteAllTypesForAlias(mKeyStore, entryAlias);
        String keyAliasInKeystore = Credentials.USER_SECRET_KEY + entryAlias;
        int errorCode = mKeyStore.importKey(
                keyAliasInKeystore,
                args,
                KeymasterDefs.KM_KEY_FORMAT_RAW,
                keyMaterial,
                params.getFlags(),
                new KeyCharacteristics());
        if (errorCode != android.security.KeyStore.NO_ERROR) {
            throw new KeyStoreException("Failed to import secret key. Keystore error code: "
                + errorCode);
        }
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] userKey, Certificate[] chain)
            throws KeyStoreException {
        throw new KeyStoreException("Operation not supported because key encoding is unknown");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
        if (isKeyEntry(alias)) {
            throw new KeyStoreException("Entry exists and is not a trusted certificate");
        }

        // We can't set something to null.
        if (cert == null) {
            throw new NullPointerException("cert == null");
        }

        final byte[] encoded;
        try {
            encoded = cert.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException(e);
        }

        if (!mKeyStore.put(Credentials.CA_CERTIFICATE + alias, encoded,
                android.security.KeyStore.UID_SELF, android.security.KeyStore.FLAG_NONE)) {
            throw new KeyStoreException("Couldn't insert certificate; is KeyStore initialized?");
        }
    }

    @Override
    public void engineDeleteEntry(String alias) throws KeyStoreException {
        if (!isKeyEntry(alias) && !isCertificateEntry(alias)) {
            return;
        }

        if (!Credentials.deleteAllTypesForAlias(mKeyStore, alias)) {
            throw new KeyStoreException("No such entry " + alias);
        }
    }

    private Set<String> getUniqueAliases() {
        final String[] rawAliases = mKeyStore.saw("");
        if (rawAliases == null) {
            return new HashSet<String>();
        }

        final Set<String> aliases = new HashSet<String>(rawAliases.length);
        for (String alias : rawAliases) {
            final int idx = alias.indexOf('_');
            if ((idx == -1) || (alias.length() <= idx)) {
                Log.e(NAME, "invalid alias: " + alias);
                continue;
            }

            aliases.add(new String(alias.substring(idx + 1)));
        }

        return aliases;
    }

    @Override
    public Enumeration<String> engineAliases() {
        return Collections.enumeration(getUniqueAliases());
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias)
                || mKeyStore.contains(Credentials.USER_SECRET_KEY + alias)
                || mKeyStore.contains(Credentials.USER_CERTIFICATE + alias)
                || mKeyStore.contains(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public int engineSize() {
        return getUniqueAliases().size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        return isKeyEntry(alias);
    }

    private boolean isKeyEntry(String alias) {
        return isPrivateKeyEntry(alias) || isSecretKeyEntry(alias);
    }

    private boolean isPrivateKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias);
    }

    private boolean isSecretKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_SECRET_KEY + alias);
    }

    private boolean isCertificateEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
        return !isKeyEntry(alias) && isCertificateEntry(alias);
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
        if (cert == null) {
            return null;
        }

        final Set<String> nonCaEntries = new HashSet<String>();

        /*
         * First scan the PrivateKeyEntry types. The KeyStoreSpi documentation
         * says to only compare the first certificate in the chain which is
         * equivalent to the USER_CERTIFICATE prefix for the Android keystore
         * convention.
         */
        final String[] certAliases = mKeyStore.saw(Credentials.USER_CERTIFICATE);
        if (certAliases != null) {
            for (String alias : certAliases) {
                final byte[] certBytes = mKeyStore.get(Credentials.USER_CERTIFICATE + alias);
                if (certBytes == null) {
                    continue;
                }

                final Certificate c = toCertificate(certBytes);
                nonCaEntries.add(alias);

                if (cert.equals(c)) {
                    return alias;
                }
            }
        }

        /*
         * Look at all the TrustedCertificateEntry types. Skip all the
         * PrivateKeyEntry we looked at above.
         */
        final String[] caAliases = mKeyStore.saw(Credentials.CA_CERTIFICATE);
        if (certAliases != null) {
            for (String alias : caAliases) {
                if (nonCaEntries.contains(alias)) {
                    continue;
                }

                final byte[] certBytes = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
                if (certBytes == null) {
                    continue;
                }

                final Certificate c =
                        toCertificate(mKeyStore.get(Credentials.CA_CERTIFICATE + alias));
                if (cert.equals(c)) {
                    return alias;
                }
            }
        }

        return null;
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        throw new UnsupportedOperationException("Can not serialize AndroidKeyStore to OutputStream");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        if (stream != null) {
            throw new IllegalArgumentException("InputStream not supported");
        }

        if (password != null) {
            throw new IllegalArgumentException("password not supported");
        }

        // Unfortunate name collision.
        mKeyStore = android.security.KeyStore.getInstance();
    }

    @Override
    public void engineSetEntry(String alias, Entry entry, ProtectionParameter param)
            throws KeyStoreException {
        if (entry == null) {
            throw new KeyStoreException("entry == null");
        }

        if (engineContainsAlias(alias)) {
            engineDeleteEntry(alias);
        }

        if (entry instanceof KeyStore.TrustedCertificateEntry) {
            KeyStore.TrustedCertificateEntry trE = (KeyStore.TrustedCertificateEntry) entry;
            engineSetCertificateEntry(alias, trE.getTrustedCertificate());
            return;
        }

        if (param != null && !(param instanceof KeyStoreParameter)) {
            throw new KeyStoreException(
                    "protParam should be android.security.KeyStoreParameter; was: "
                    + param.getClass().getName());
        }

        if (entry instanceof PrivateKeyEntry) {
            PrivateKeyEntry prE = (PrivateKeyEntry) entry;
            setPrivateKeyEntry(alias, prE.getPrivateKey(), prE.getCertificateChain(),
                    (KeyStoreParameter) param);
        } else if (entry instanceof SecretKeyEntry) {
            SecretKeyEntry secE = (SecretKeyEntry) entry;
            setSecretKeyEntry(alias, secE.getSecretKey(), (KeyStoreParameter) param);
        } else {
            throw new KeyStoreException(
                    "Entry must be a PrivateKeyEntry, SecretKeyEntry or TrustedCertificateEntry"
                    + "; was " + entry);
        }
    }

}
