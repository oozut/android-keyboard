/*
 * Copyright (C) 2014 The Android Open Source Project
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

package org.futo.inputmethod.latin.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.futo.inputmethod.latin.network.BlockingHttpClient.ResponseProcessor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.Random;

/**
 * Tests for {@link BlockingHttpClient}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BlockingHttpClientTests {
    /** Hand-written fake; avoids Dexmaker, which cannot load writable dex files on API 34+. */
    private static class FakeHttpConnection extends HttpURLConnection {
        int mResponseCode = HTTP_OK;
        InputStream mInputStream = new ByteArrayInputStream(new byte[0]);
        OutputStream mOutputStream = new ByteArrayOutputStream();
        boolean mDisconnected;

        FakeHttpConnection() {
            super(null);
        }

        @Override
        public void disconnect() {
            mDisconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public int getResponseCode() {
            return mResponseCode;
        }

        @Override
        public String getResponseMessage() {
            return "fake";
        }

        @Override
        public InputStream getInputStream() {
            return mInputStream;
        }

        @Override
        public OutputStream getOutputStream() {
            return mOutputStream;
        }
    }

    @Test
    public void testError_badGateway() throws IOException, AuthException {
        final FakeHttpConnection connection = new FakeHttpConnection();
        connection.mResponseCode = HttpURLConnection.HTTP_BAD_GATEWAY;
        final BlockingHttpClient client = new BlockingHttpClient(connection);
        final FakeErrorResponseProcessor processor = new FakeErrorResponseProcessor();

        try {
            client.execute(null /* empty request */, processor);
            fail("Expecting an HttpException");
        } catch (HttpException e) {
            // expected HttpException
            assertEquals(HttpURLConnection.HTTP_BAD_GATEWAY, e.getHttpStatusCode());
        }
    }

    @Test
    public void testError_clientTimeout() throws Exception {
        final FakeHttpConnection connection = new FakeHttpConnection();
        connection.mResponseCode = HttpURLConnection.HTTP_CLIENT_TIMEOUT;
        final BlockingHttpClient client = new BlockingHttpClient(connection);
        final FakeErrorResponseProcessor processor = new FakeErrorResponseProcessor();

        try {
            client.execute(null /* empty request */, processor);
            fail("Expecting an HttpException");
        } catch (HttpException e) {
            // expected HttpException
            assertEquals(HttpURLConnection.HTTP_CLIENT_TIMEOUT, e.getHttpStatusCode());
        }
    }

    @Test
    public void testError_forbiddenWithRequest() throws Exception {
        final FakeHttpConnection connection = new FakeHttpConnection();
        connection.mResponseCode = HttpURLConnection.HTTP_FORBIDDEN;
        final BlockingHttpClient client = new BlockingHttpClient(connection);
        final FakeErrorResponseProcessor processor = new FakeErrorResponseProcessor();

        try {
            client.execute(new byte[100], processor);
            fail("Expecting an HttpException");
        } catch (HttpException e) {
            assertEquals(HttpURLConnection.HTTP_FORBIDDEN, e.getHttpStatusCode());
        }
        assertTrue("Request was not written", connection.mOutputStream instanceof ByteArrayOutputStream);
        assertEquals(100, ((ByteArrayOutputStream) connection.mOutputStream).size());
    }

    @Test
    public void testSuccess_emptyRequest() throws Exception {
        final Random rand = new Random();
        byte[] response = new byte[100];
        rand.nextBytes(response);
        final FakeHttpConnection connection = new FakeHttpConnection();
        connection.mResponseCode = HttpURLConnection.HTTP_OK;
        connection.mInputStream = new ByteArrayInputStream(response);
        final BlockingHttpClient client = new BlockingHttpClient(connection);
        final FakeSuccessResponseProcessor processor =
                new FakeSuccessResponseProcessor(response);

        client.execute(null /* empty request */, processor);
        assertTrue("ResponseProcessor was not invoked", processor.mInvoked);
    }

    @Test
    public void testSuccess() throws Exception {
        final FakeHttpConnection connection = new FakeHttpConnection();
        final Random rand = new Random();
        byte[] response = new byte[100];
        rand.nextBytes(response);
        connection.mResponseCode = HttpURLConnection.HTTP_OK;
        connection.mInputStream = new ByteArrayInputStream(response);
        final BlockingHttpClient client = new BlockingHttpClient(connection);
        final FakeSuccessResponseProcessor processor =
                new FakeSuccessResponseProcessor(response);

        client.execute(new byte[100], processor);
        assertTrue("ResponseProcessor was not invoked", processor.mInvoked);
    }

    static class FakeErrorResponseProcessor implements ResponseProcessor<Void> {
        @Override
        public Void onSuccess(InputStream response) {
            fail("Expected an error but received success");
            return null;
        }
    }

    private static class FakeSuccessResponseProcessor implements ResponseProcessor<Void> {
        private final byte[] mExpectedResponse;

        boolean mInvoked;

        FakeSuccessResponseProcessor(byte[] expectedResponse) {
            mExpectedResponse = expectedResponse;
        }

        @Override
        public Void onSuccess(InputStream response) {
            try {
                mInvoked = true;
                BufferedInputStream in = new BufferedInputStream(response);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int read = 0;
                while ((read = in.read()) != -1) {
                    buffer.write(read);
                }
                byte[] actualResponse = buffer.toByteArray();
                in.close();
                assertTrue("Response doesn't match",
                        Arrays.equals(mExpectedResponse, actualResponse));
            } catch (IOException ex) {
                fail("IOException in onSuccess");
            }
            return null;
        }
    }
}
