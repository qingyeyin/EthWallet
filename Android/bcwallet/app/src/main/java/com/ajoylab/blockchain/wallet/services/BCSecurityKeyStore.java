package com.ajoylab.blockchain.wallet.services;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

@TargetApi(23)
public class BCSecurityKeyStore
{
    private static final String TAG = "###BCSecurityKeyStore";

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC;
    private static final String PADDING = KeyProperties.ENCRYPTION_PADDING_PKCS7;
    private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS7Padding";

    public synchronized static boolean setData(Context context,
                                                byte[] data,
                                                String alias,
                                                String aliasFile,
                                                String aliasIV) throws BCKeyStoreException {
        if (null == data) {
            throw new BCKeyStoreException(BCKeyStoreException.INVALID_DATA, "keystore setData data is null");
        }

        KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            // Create the keys if necessary
            if (!keyStore.containsAlias(alias)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);

                // Set the alias of the entry in Android KeyStore where the key will appear
                // and the constrains (purposes) in the constructor of the Builder
                keyGenerator.init(new KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(BLOCK_MODE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .setRandomizedEncryptionRequired(true)
                        .setEncryptionPaddings(PADDING)
                        .build());
                keyGenerator.generateKey();
            }

            String encryptedDataFilePath = getFilePath(context, aliasFile);
            SecretKey secret = (SecretKey)keyStore.getKey(alias, null);
            if (null == secret) {
                throw new BCKeyStoreException(BCKeyStoreException.KEY_STORE_SECRET, "secret is null on setData: " + alias);
            }

            Cipher inCipher = Cipher.getInstance(CIPHER_ALGORITHM);
            inCipher.init(Cipher.ENCRYPT_MODE, secret);
            byte[] iv = inCipher.getIV();
            String path = getFilePath(context, aliasIV);
            boolean success = writeBytesToFile(path, iv);
            if (!success) {
                keyStore.deleteEntry(alias);
                throw new BCKeyStoreException(BCKeyStoreException.FAIL_TO_SAVE_IV_FILE, "Failed to save the iv file for: " + alias);
            }
            CipherOutputStream cipherOutputStream = null;
            try {
                cipherOutputStream = new CipherOutputStream(new FileOutputStream(encryptedDataFilePath), inCipher);
                cipherOutputStream.write(data);
            } catch (Exception ex) {
                throw new BCKeyStoreException(BCKeyStoreException.KEY_STORE_ERROR, "Failed to save the file for: " + alias);
            } finally {
                if (cipherOutputStream != null) {
                    cipherOutputStream.close();
                }
            }
            return true;
        } catch (UserNotAuthenticatedException e) {
            throw new BCKeyStoreException(BCKeyStoreException.USER_NOT_AUTHENTICATED, null);
        } catch (BCKeyStoreException ex) {
            Log.d(TAG, "Key store error", ex);
            throw ex;
        } catch (Exception ex) {
            Log.d(TAG, "Key store error", ex);
            throw new BCKeyStoreException(BCKeyStoreException.KEY_STORE_ERROR, null);
        }
    }

    public synchronized static byte[] getData(final Context context, String alias, String aliasFile, String aliasIV) throws BCKeyStoreException {
        KeyStore keyStore;
        String encryptedDataFilePath = getFilePath(context, aliasFile);
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            SecretKey secretKey = (SecretKey) keyStore.getKey(alias, null);
            if (secretKey == null) {
                /* no such key, the key is just simply not there */
                boolean fileExists = new File(encryptedDataFilePath).exists();
                if (!fileExists) {
                    return null;/* file also not there, fine then */
                }
                throw new BCKeyStoreException(BCKeyStoreException.KEY_IS_GONE, "file is present but the key is gone: " + alias);
            }

            boolean ivExists = new File(getFilePath(context, aliasIV)).exists();
            boolean aliasExists = new File(getFilePath(context, aliasFile)).exists();
            if (!ivExists || !aliasExists) {
                removeAliasAndFiles(context, alias, aliasFile, aliasIV);
                //report it if one exists and not the other.
                if (ivExists != aliasExists) {
                    throw new BCKeyStoreException(BCKeyStoreException.IV_OR_ALIAS_NO_ON_DISK, "file is present but the key is gone: " + alias);
                } else {
                    throw new BCKeyStoreException(BCKeyStoreException.IV_OR_ALIAS_NO_ON_DISK, "!ivExists && !aliasExists: " + alias);
                }
            }

            byte[] iv = readBytesFromFile(getFilePath(context, aliasIV));
            if (iv == null || iv.length == 0) {
                throw new NullPointerException("iv is missing for " + alias);
            }
            Cipher outCipher = Cipher.getInstance(CIPHER_ALGORITHM);
            outCipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
            CipherInputStream cipherInputStream = new CipherInputStream(new FileInputStream(encryptedDataFilePath), outCipher);
            return readBytesFromStream(cipherInputStream);
        } catch (InvalidKeyException e) {
            if (e instanceof UserNotAuthenticatedException) {
//				showAuthenticationScreen(context, requestCode);
                throw new BCKeyStoreException(BCKeyStoreException.USER_NOT_AUTHENTICATED, null);
            } else {
                throw new BCKeyStoreException(BCKeyStoreException.INVALID_KEY, null);
            }
        } catch (IOException | CertificateException | KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException e) {
            throw new BCKeyStoreException(BCKeyStoreException.KEY_STORE_ERROR, null);
        }
    }

    private synchronized static String getFilePath(Context context, String fileName) {
        return new File(context.getFilesDir(), fileName).getAbsolutePath();
    }

    private static boolean writeBytesToFile(String path, byte[] data) {
        FileOutputStream fos = null;
        try {
            File file = new File(path);
            fos = new FileOutputStream(file);
            // Writes bytes from the specified byte array to this file output stream
            fos.write(data);
            return true;
        } catch (FileNotFoundException e) {
            System.out.println("File not found" + e);
        } catch (IOException ioe) {
            System.out.println("Exception while writing file " + ioe);
        } finally {
            // close the streams using close method
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException ioe) {
                System.out.println("Error while closing stream: " + ioe);
            }
        }
        return false;
    }

    private synchronized static void removeAliasAndFiles(Context context, String alias, String dataFileName, String ivFileName) {
        KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            keyStore.deleteEntry(alias);
            new File(getFilePath(context, dataFileName)).delete();
            new File(getFilePath(context, ivFileName)).delete();
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
        }
    }

    private static byte[] readBytesFromStream(InputStream in) {
        // this dynamically extends to take the bytes you read
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        // this is storage overwritten on each iteration with bytes
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];
        // we need to know how may bytes were read to write them to the byteBuffer
        int len;
        try {
            while ((len = in.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                byteBuffer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (in != null) try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // and then we can return your byte array.
        return byteBuffer.toByteArray();
    }

    private static byte[] readBytesFromFile(String path) {
        byte[] bytes = null;
        FileInputStream fin;
        try {
            File file = new File(path);
            fin = new FileInputStream(file);
            bytes = readBytesFromStream(fin);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bytes;
    }
}
