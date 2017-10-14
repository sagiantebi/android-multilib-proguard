package com.sagiantebi.submodule_one;

import com.sagiantebi.mainlibrary.JustAnotherClass;

/**
 * Created by sagiantebi on 9/25/17.
 */

public class OneEntryClass {

    private JustAnotherClass mRef;

    /*package*/ OneEntryClass() {
        mRef = new JustAnotherClass();
    }

    public void doWork() {
        mRef.invokedFromOne();
    }


}
