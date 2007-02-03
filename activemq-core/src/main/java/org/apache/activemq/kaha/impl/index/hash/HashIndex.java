/**
 * 
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.apache.activemq.kaha.impl.index.hash;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.activemq.kaha.Marshaller;
import org.apache.activemq.kaha.StoreEntry;
import org.apache.activemq.kaha.impl.index.Index;
import org.apache.activemq.kaha.impl.index.IndexManager;
import org.apache.activemq.util.DataByteArrayInputStream;
import org.apache.activemq.util.DataByteArrayOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * BTree implementation
 * 
 * @version $Revision: 1.1.1.1 $
 */
public class HashIndex implements Index{

    private static final String NAME_PREFIX="tree-index-";
    private static final int DEFAULT_PAGE_SIZE;
    private static final int DEFAULT_KEY_SIZE;
    private static final Log log=LogFactory.getLog(HashIndex.class);
    private final String name;
    private File directory;
    private File file;
    private RandomAccessFile indexFile;
    private IndexManager indexManager;
    private int pageSize=DEFAULT_PAGE_SIZE;
    private int keySize=DEFAULT_KEY_SIZE;
    private int keysPerPage=pageSize/keySize;
    private DataByteArrayInputStream dataIn;
    private DataByteArrayOutputStream dataOut;
    private byte[] readBuffer;
    private HashBin[] bins;
    private Marshaller keyMarshaller;
    private long length=0;
    private HashPage firstFree;
    private HashPage lastFree;
    private AtomicBoolean loaded=new AtomicBoolean();

    /**
     * Constructor
     * 
     * @param directory
     * @param name
     * @param indexManager
     * @throws IOException
     */
    public HashIndex(File directory,String name,IndexManager indexManager) throws IOException{
        this(directory,name,indexManager,1024);
    }

    /**
     * Constructor
     * 
     * @param directory
     * @param name
     * @param indexManager
     * @param numberOfBins
     * @throws IOException
     */
    public HashIndex(File directory,String name,IndexManager indexManager,int numberOfBins) throws IOException{
        this.directory=directory;
        this.name=name;
        this.indexManager=indexManager;
        int capacity=1;
        while(capacity<numberOfBins)
            capacity<<=1;
        this.bins=new HashBin[capacity];
        openIndexFile();
    }

    /**
     * Set the marshaller for key objects
     * 
     * @param marshaller
     */
    public void setKeyMarshaller(Marshaller marshaller){
        this.keyMarshaller=marshaller;
    }

    /**
     * @return the keySize
     */
    public int getKeySize(){
        return this.keySize;
    }

    /**
     * @param keySize the keySize to set
     */
    public void setKeySize(int keySize){
        this.keySize=keySize;
        if(loaded.get()){
            throw new RuntimeException("Pages already loaded - can't reset key size");
        }
    }

    /**
     * @return the pageSize
     */
    public int getPageSize(){
        return this.pageSize;
    }

    /**
     * @param pageSize the pageSize to set
     */
    public void setPageSize(int pageSize){
        if(loaded.get()&&pageSize!=this.pageSize){
            throw new RuntimeException("Pages already loaded - can't reset page size");
        }
        this.pageSize=pageSize;
    }

    public boolean isTransient(){
        return false;
    }

    public void load(){
        if(loaded.compareAndSet(false,true)){
            keysPerPage=pageSize/keySize;
            dataIn=new DataByteArrayInputStream();
            dataOut=new DataByteArrayOutputStream(pageSize);
            readBuffer=new byte[pageSize];
            try{
                openIndexFile();
                long offset=0;
                while((offset+pageSize)<=indexFile.length()){
                    indexFile.seek(offset);
                    indexFile.readFully(readBuffer,0,HashPage.PAGE_HEADER_SIZE);
                    dataIn.restart(readBuffer);
                    HashPage page=new HashPage(keysPerPage);
                    page.setId(offset);
                    page.readHeader(dataIn);
                    if(!page.isActive()){
                        if(lastFree!=null){
                            lastFree.setNextFreePageId(offset);
                            indexFile.seek(lastFree.getId());
                            dataOut.reset();
                            lastFree.writeHeader(dataOut);
                            indexFile.write(dataOut.getData(),0,HashPage.PAGE_HEADER_SIZE);
                            lastFree=page;
                        }else{
                            lastFree=firstFree=page;
                        }
                    }else{
                        addToBin(page);
                    }
                    offset+=pageSize;
                }
                length=offset;
            }catch(IOException e){
                log.error("Failed to load index ",e);
                throw new RuntimeException(e);
            }
        }
    }

    public void unload() throws IOException{
        if(loaded.compareAndSet(true,false)){
            if(indexFile!=null){
                indexFile.close();
                indexFile=null;
                firstFree=lastFree=null;
                bins=new HashBin[bins.length];
            }
        }
    }

    public void store(Object key,StoreEntry value) throws IOException{
        HashEntry entry=new HashEntry();
        entry.setKey((Comparable)key);
        entry.setIndexOffset(value.getOffset());
        getBin(key).put(entry);
    }

    public StoreEntry get(Object key) throws IOException{
        HashEntry entry=new HashEntry();
        entry.setKey((Comparable)key);
        HashEntry result=getBin(key).find(entry);
        return result!=null?indexManager.getIndex(result.getIndexOffset()):null;
    }

    public void remove(Object key) throws IOException{
        HashEntry entry=new HashEntry();
        entry.setKey((Comparable)key);
        getBin(key).remove(entry);
    }

    public boolean containsKey(Object key) throws IOException{
        return get(key)!=null;
    }

    public void clear() throws IOException{
        unload();
        delete();
        openIndexFile();
        load();
    }

    public void delete() throws IOException{
        unload();
        if(file.exists()){
            boolean result=file.delete();
        }
        length=0;
    }

    HashPage lookupPage(long pageId) throws IOException{
        HashPage result=null;
        if(pageId>=0){
            result=getFullPage(pageId);
            if(result!=null){
                if(result.isActive()){
                }else{
                    throw new IllegalStateException("Trying to access an inactive page: "+pageId);
                }
            }
        }
        return result;
    }

    HashPage createPage(int binId) throws IOException{
        HashPage result=getNextFreePage();
        if(result==null){
            // allocate one
            result=new HashPage(keysPerPage);
            result.setId(length);
            result.setBinId(binId);
            writePageHeader(result);
            length+=pageSize;
            indexFile.seek(length);
            indexFile.write(HashEntry.NOT_SET);
        }
        return result;
    }

    void releasePage(HashPage page) throws IOException{
        page.reset();
        page.setActive(false);
        if(lastFree==null){
            firstFree=lastFree=page;
        }else{
            lastFree.setNextFreePageId(page.getId());
            writePageHeader(lastFree);
        }
        writePageHeader(page);
    }

    private HashPage getNextFreePage() throws IOException{
        HashPage result=null;
        if(firstFree!=null){
            if(firstFree.equals(lastFree)){
                result=firstFree;
                firstFree=lastFree=null;
            }else{
                result=firstFree;
                firstFree=getPageHeader(firstFree.getNextFreePageId());
                if(firstFree==null){
                    lastFree=null;
                }
            }
            result.setActive(true);
            result.reset();
            writePageHeader(result);
        }
        return result;
    }

    void writeFullPage(HashPage page) throws IOException{
        dataOut.reset();
        page.write(keyMarshaller,dataOut);
        if(dataOut.size()>pageSize){
            throw new IOException("Page Size overflow: pageSize is "+pageSize+" trying to write "+dataOut.size());
        }
        indexFile.seek(page.getId());
        indexFile.write(dataOut.getData(),0,dataOut.size());
    }

    void writePageHeader(HashPage page) throws IOException{
        dataOut.reset();
        page.writeHeader(dataOut);
        indexFile.seek(page.getId());
        indexFile.write(dataOut.getData(),0,HashPage.PAGE_HEADER_SIZE);
    }

    HashPage getFullPage(long id) throws IOException{
        indexFile.seek(id);
        indexFile.readFully(readBuffer,0,pageSize);
        dataIn.restart(readBuffer);
        HashPage page=new HashPage(keysPerPage);
        page.setId(id);
        page.read(keyMarshaller,dataIn);
        return page;
    }

    HashPage getPageHeader(long id) throws IOException{
        indexFile.seek(id);
        indexFile.readFully(readBuffer,0,HashPage.PAGE_HEADER_SIZE);
        dataIn.restart(readBuffer);
        HashPage page=new HashPage(keysPerPage);
        page.setId(id);
        page.readHeader(dataIn);
        return page;
    }

    void addToBin(HashPage page){
        HashBin bin=getBin(page.getBinId());
        bin.addHashPageInfo(page.getId(),page.getPersistedSize());
    }

    private HashBin getBin(int index){
        HashBin result=bins[index];
        if(result==null){
            result=new HashBin(this,index,pageSize/keySize);
            bins[index]=result;
        }
        return result;
    }

    private void openIndexFile() throws IOException{
        if(indexFile==null){
            file=new File(directory,NAME_PREFIX+name);
            indexFile=new RandomAccessFile(file,"rw");
        }
    }

    private HashBin getBin(Object key){
        int hash=hash(key);
        int i=indexFor(hash,bins.length);
        return getBin(i);
    }

    static int hash(Object x){
        int h=x.hashCode();
        h+=~(h<<9);
        h^=(h>>>14);
        h+=(h<<4);
        h^=(h>>>10);
        return h;
    }

    static int indexFor(int h,int length){
        return h&(length-1);
    }
    static{
        DEFAULT_PAGE_SIZE=Integer.parseInt(System.getProperty("defaultPageSize","16384"));
        DEFAULT_KEY_SIZE=Integer.parseInt(System.getProperty("defaultKeySize","96"));
    }
}
