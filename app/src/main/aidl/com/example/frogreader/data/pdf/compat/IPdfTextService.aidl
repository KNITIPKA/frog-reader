package com.example.frogreader.data.pdf.compat;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** The text parser receives a read-only descriptor, never a source path or storage permission. */
interface IPdfTextService {
    Bundle open(in ParcelFileDescriptor source, String password, IBinder owner);
    Bundle request(in Bundle request);
    Bundle writeAnnotations(in ParcelFileDescriptor target, in ParcelFileDescriptor annotations);
    void close();
}
