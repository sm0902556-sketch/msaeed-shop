package com.msaeed.ghost;

// الدخول بقى على Firebase زي برنامج الإخوة (المدير M saeed بيضيف العملاء ويوقفهم).
// هنا بس بنعرف إن فيه حد داخل، عشان البرنامج مايشتغلش من غير دخول.
// المبرمج محمد سعيد - 01123840671

import android.content.Context;

public class Lock {
    private static volatile boolean open = false;
    static boolean isOpen(Context c) { return open; }
    static void set(boolean o) { open = o; }
    static void logout() { open = false; }
}
