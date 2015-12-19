// Copyright (c) 2014-2015 K Team. All Rights Reserved.
package org.kframework.kil.visitors;

import org.kframework.kil.loader.Context;

public class NonCachingVisitor extends BasicVisitor {

    public NonCachingVisitor() { super(null); }
    public NonCachingVisitor(Context context) {
        super(context);
    }

    @Override
    public boolean cache() {
        return false;
    }
}
