/*
 * Copyright (C) 2024 The Android Open Source Project
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

package org.futo.inputmethod.keyboard.internal;

import android.test.AndroidTestCase;

/**
 * Tests for the V2 keyboard state machine used by the shipped keyboard.
 *
 * The older KeyboardState{Single,Multi}TouchTests fixtures describe the AOSP state machine that
 * predates the page based KeyboardLayoutElement model. They are kept as reference (with their
 * test methods renamed) but cannot express states such as ALPHABET_SHIFT_LOCK_SHIFTED that no
 * longer exist. This class covers the behaviour of the current implementation.
 */
public class KeyboardStateV2Tests extends AndroidTestCase
        implements MockKeyboardSwitcher.MockConstants {
    private MockKeyboardSwitcher mSwitcher;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSwitcher = new MockKeyboardSwitcher();
        mSwitcher.setAutoCapsMode(CAP_MODE_OFF);
        mSwitcher.loadKeyboard();
    }

    private void press(final int code) {
        mSwitcher.onPressKey(code, SINGLE);
    }

    private void release(final int code) {
        mSwitcher.onReleaseKey(code, NOT_SLIDING);
    }

    private void assertLayout(String message, int expected) {
        assertEquals(message, MockKeyboardSwitcher.getLayoutName(expected),
                MockKeyboardSwitcher.getLayoutName(mSwitcher.getLayoutId()));
    }

    public void testShiftTapTogglesOnPress() {
        assertLayout("initial", ALPHABET_UNSHIFTED);

        press(CODE_SHIFT);
        assertLayout("first shift press", ALPHABET_MANUAL_SHIFTED);
        release(CODE_SHIFT);
        assertLayout("first shift release", ALPHABET_MANUAL_SHIFTED);

        mSwitcher.expireDoubleTapTimeout();
        press(CODE_SHIFT);
        assertLayout("second shift press", ALPHABET_UNSHIFTED);
        release(CODE_SHIFT);
        assertLayout("second shift release", ALPHABET_UNSHIFTED);
    }

    public void testDoubleTapShiftLocksOnSecondPress() {
        press(CODE_SHIFT);
        release(CODE_SHIFT);
        assertLayout("after first tap", ALPHABET_MANUAL_SHIFTED);

        // No expireDoubleTapTimeout here: the second tap is inside the double-tap window.
        press(CODE_SHIFT);
        assertLayout("second tap locks", ALPHABET_SHIFT_LOCKED);
        release(CODE_SHIFT);
        assertLayout("lock survives release", ALPHABET_SHIFT_LOCKED);

        mSwitcher.expireDoubleTapTimeout();
        press(CODE_SHIFT);
        assertLayout("tapping a locked shift unlocks", ALPHABET_UNSHIFTED);
        release(CODE_SHIFT);
        assertLayout("unlocked after release", ALPHABET_UNSHIFTED);
    }

    public void testShiftedSymbolsExitOnSpace() {
        press(CODE_SYMBOL);
        release(CODE_SYMBOL);
        assertLayout("symbols", SYMBOLS_UNSHIFTED);

        press(CODE_SHIFT);
        release(CODE_SHIFT);
        assertLayout("shifted symbols", SYMBOLS_SHIFTED);

        press(CODE_SPACE);
        release(CODE_SPACE);
        assertLayout("space leaves shifted symbols", ALPHABET_UNSHIFTED);
    }
}
