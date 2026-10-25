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

package org.futo.inputmethod.latin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.provider.ContactsContract.Contacts;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Tests for {@link ContactsContentObserver}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ContactsContentObserverTest {
    private static final int UPDATED_CONTACT_COUNT = 10;
    private static final int STALE_CONTACT_COUNT = 8;
    private static final ArrayList<String> STALE_NAMES_LIST = new ArrayList<>();
    private static final ArrayList<String> UPDATED_NAMES_LIST = new ArrayList<>();

    static {
        STALE_NAMES_LIST.add("Larry Page");
        STALE_NAMES_LIST.add("Roger Federer");
        UPDATED_NAMES_LIST.add("Larry Page");
        UPDATED_NAMES_LIST.add("Roger Federer");
        UPDATED_NAMES_LIST.add("Barak Obama");
    }

    /** Hand-written fake; avoids Dexmaker, which cannot load writable dex files on API 34+. */
    private static class FakeContactsManager extends ContactsManager {
        int mContactCount = STALE_CONTACT_COUNT;
        int mContactCountAtLastRebuild = STALE_CONTACT_COUNT;
        int mHashCodeAtLastRebuild = STALE_NAMES_LIST.hashCode();
        ArrayList<String> mNames = STALE_NAMES_LIST;

        FakeContactsManager(Context context) {
            super(context);
        }

        @Override
        public int getContactCount() {
            return mContactCount;
        }

        @Override
        public int getContactCountAtLastRebuild() {
            return mContactCountAtLastRebuild;
        }

        @Override
        public int getHashCodeAtLastRebuild() {
            return mHashCodeAtLastRebuild;
        }

        @Override
        public ArrayList<String> getValidNames(android.net.Uri uri) {
            return mNames;
        }
    }

    private FakeContactsManager mManager;
    private ContactsContentObserver mObserver;

    @Before
    public void setUp() {
        final android.app.Instrumentation instrumentation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        final Context testContext = instrumentation.getContext();
        try {
            instrumentation.getUiAutomation().grantRuntimePermission(
                    testContext.getPackageName(), Manifest.permission.READ_CONTACTS);
        } catch (SecurityException | IllegalArgumentException e) {
            // Already granted, or the target SDK handles the permission itself.
        }
        final Context context = new ContextWrapper(testContext) {
            @Override
            public int checkSelfPermission(String permission) {
                return PackageManager.PERMISSION_GRANTED;
            }

            @Override
            public int checkPermission(String permission, int pid, int uid) {
                return PackageManager.PERMISSION_GRANTED;
            }

            @Override
            public int checkCallingOrSelfPermission(String permission) {
                return PackageManager.PERMISSION_GRANTED;
            }

            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
        mManager = new FakeContactsManager(context);
        mObserver = new ContactsContentObserver(mManager, context);
    }

    @Test
    public void testHaveContentsChanged_NoChange() {
        assertFalse(mObserver.haveContentsChanged());
    }

    @Test
    public void testHaveContentsChanged_UpdatedCount() {
        mManager.mContactCount = UPDATED_CONTACT_COUNT;
        assertTrue(mObserver.haveContentsChanged());
    }

    @Test
    public void testHaveContentsChanged_HashUpdate() {
        mManager.mNames = UPDATED_NAMES_LIST;
        assertTrue(mObserver.haveContentsChanged());
    }
}
