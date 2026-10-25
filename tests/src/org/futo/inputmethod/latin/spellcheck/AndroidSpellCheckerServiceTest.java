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

package org.futo.inputmethod.latin.spellcheck;

import android.test.suitebuilder.annotation.LargeTest;
import android.text.style.SuggestionSpan;

import org.futo.inputmethod.latin.InputTestsBase;

/**
 * The IME manifest sets android:suppressesSpellChecker="true" because this fork provides its
 * own suggestions and spell checking through the keyboard. The framework therefore does not
 * attach SuggestionSpans to editors while the IME is shown, so these tests assert that the
 * suppression is effective instead of expecting framework-generated suggestions.
 */
@LargeTest
public class AndroidSpellCheckerServiceTest extends InputTestsBase {
    private void assertSystemSpellCheckerSuppressed(final String text) {
        mEditText.setText(text);
        mEditText.setSelection(mEditText.getText().length());
        mEditText.onAttachedToWindow();
        sleep(1000);
        runMessages();
        sleep(1000);

        final SpanGetter span = new SpanGetter(mEditText.getText(), SuggestionSpan.class);
        assertNull("System spell checker must be suppressed while the IME is active",
                span.getSpan());
    }

    public void testSpellcheckerIsSuppressed() {
        changeLanguage("en_US");
        assertSystemSpellCheckerSuppressed("tgis ");
    }

    public void testRussianSpellchecker() {
        changeLanguage("ru");
        assertSystemSpellCheckerSuppressed("годп ");
    }

    public void testSpellcheckWithPeriodsIsSuppressed() {
        changeLanguage("en_US");
        assertSystemSpellCheckerSuppressed("I'm.sure ");
    }
}
