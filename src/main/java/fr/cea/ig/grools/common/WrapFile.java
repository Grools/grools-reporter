package fr.cea.ig.grools.common;
/*
 * Copyright LABGeM 24/02/15
 *
 * author: Jonathan MERCIER
 *
 * This software is a computer program whose purpose is to annotate a complete genome.
 *
 * This software is governed by the CeCILL  license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 */


import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 *
 */
/*
 * @startuml
 * class WrapFile{
 * }
 * @enduml
 */
public class  WrapFile {
    private static final Logger LOG = (Logger) LoggerFactory.getLogger(WrapFile.class);
    protected static final String exepected     = "#C1FFC1";
    protected static final String unexepected   = "#FFC0CB";
    protected static final String newline       = System.getProperty("line.separator");
    protected static final int PAGE_SIZE        = 4096;

    protected final File file;
    protected final BufferedOutputStream bos;
    protected boolean isClosed;

    public WrapFile(final String filepath) throws IOException {
        this(new File(filepath));
    }

    public WrapFile( final File file) throws IOException {
        if (!file.getParentFile().exists()) {
            final File parentFile     = file.getParentFile();
            final boolean isCreated   = parentFile.mkdirs();
        if( ! isCreated )
            LOG.info("Directory " + file.getParentFile() + "exists already");
        }
        this.file = file;
        this.bos = new BufferedOutputStream(new FileOutputStream(file), 10 * PAGE_SIZE);
        isClosed = false;
    }

    public void writeln( final String line ) throws IOException {
        final String tmp = line + newline;
        bos.write( tmp.getBytes("UTF-8") );
    }

    public void close() throws IOException {
        isClosed = true;
        bos.close();
    }

    public boolean isClosed(){
        return isClosed;
    }

    public String getFileName(){
        return file.getName();
    }

    public String getDirectory(){
        return file.getParent();
    }

    public String getAbsolutePath(){
        return file.getAbsolutePath();
    }

    public String getPath(){
        return file.getPath();
    }


    public void finalize() throws Throwable {
        if( ! isClosed )
            close();
        super.finalize();
    }
}
