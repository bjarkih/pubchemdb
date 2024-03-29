/*
 * Copyright 2011 The Scripps Research Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.scripps.fl.pubchem.web.pug;

import edu.scripps.fl.pubchem.web.entrez.EUtilsSoapFactory;
import edu.scripps.fl.pubchem.web.entrez.EntrezHistoryKey;
import gov.nih.nlm.ncbi.pubchem.PUGStub;
import gov.nih.nlm.ncbi.pubchem.PUGStub.AnyKeyType;
import gov.nih.nlm.ncbi.pubchem.PUGStub.ArrayOfInt;
import gov.nih.nlm.ncbi.pubchem.PUGStub.AssayColumnsType;
import gov.nih.nlm.ncbi.pubchem.PUGStub.AssayDownload;
import gov.nih.nlm.ncbi.pubchem.PUGStub.AssayFormatType;
import gov.nih.nlm.ncbi.pubchem.PUGStub.CompressType;
import gov.nih.nlm.ncbi.pubchem.PUGStub.Download;
import gov.nih.nlm.ncbi.pubchem.PUGStub.EntrezKey;
import gov.nih.nlm.ncbi.pubchem.PUGStub.FormatType;
import gov.nih.nlm.ncbi.pubchem.PUGStub.GetDownloadUrl;
import gov.nih.nlm.ncbi.pubchem.PUGStub.GetEntrezKey;
import gov.nih.nlm.ncbi.pubchem.PUGStub.GetEntrezKeyResponse;
import gov.nih.nlm.ncbi.pubchem.PUGStub.GetIDList;
import gov.nih.nlm.ncbi.pubchem.PUGStub.GetOperationStatus;
import gov.nih.nlm.ncbi.pubchem.PUGStub.GetStatusMessage;
import gov.nih.nlm.ncbi.pubchem.PUGStub.IdentitySearch;
import gov.nih.nlm.ncbi.pubchem.PUGStub.IdentitySearchOptions;
import gov.nih.nlm.ncbi.pubchem.PUGStub.IdentityType;
import gov.nih.nlm.ncbi.pubchem.PUGStub.InputAssay;
import gov.nih.nlm.ncbi.pubchem.PUGStub.InputEntrez;
import gov.nih.nlm.ncbi.pubchem.PUGStub.InputList;
import gov.nih.nlm.ncbi.pubchem.PUGStub.InputStructure;
import gov.nih.nlm.ncbi.pubchem.PUGStub.PCIDType;
import gov.nih.nlm.ncbi.pubchem.PUGStub.StatusType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.zip.GZIPInputStream;

import org.apache.axis2.AxisFault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PUGSoapFactory {

	private static final Logger log = LoggerFactory.getLogger(PUGSoapFactory.class);

	private static PUGSoapFactory instance;

	public static PUGSoapFactory getInstance() {
		if (instance == null) {
			synchronized (PUGSoapFactory.class) { // 1
				if (instance == null) {
					synchronized (PUGSoapFactory.class) { // 3
						// inst = new Singleton(); //4
						instance = new PUGSoapFactory();
					}
					// instance = inst; //5
				}
			}
		}
		return instance;
	}

	private PUGStub pug;

	private int initSleepTime = 2000;
	private int sleepTime = 6000;

	private PUGSoapFactory() {
		try {
			pug = new PUGStub();
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException(ex);
		}
	}

	protected AnyKeyType downloadAssay(String assayKey, CompressType compressType) throws RemoteException {
		AssayDownload download = new AssayDownload();
		download.setAssayKey(assayKey);
		download.setAssayFormat(AssayFormatType.eAssayFormat_CSV);
		download.setECompress(compressType);
		String downloadKey = getPUG().assayDownload(download).getDownloadKey();
		AnyKeyType anyKey = new AnyKeyType();
		anyKey.setAnyKey(downloadKey);
		return anyKey;
	}

	@Override
	public void finalize() throws Throwable {
		PUGStub pug = getPUG();
		if (pug != null) {
			pug.cleanup();
			pug = null;
		}
	}

	protected InputStream getAssayDownload(AnyKeyType anyKey, StatusType status) throws Exception {
		if (status == StatusType.eStatus_Success) {
			GetDownloadUrl getURL = new GetDownloadUrl();
			getURL.setDownloadKey(anyKey.getAnyKey());
			URL url = new URL(getPUG().getDownloadUrl(getURL).getUrl());
			log.debug("Success! Download URL = " + url.toString());
			return url.openStream();
		} else {
			GetStatusMessage message = new GetStatusMessage();
			message.setGetStatusMessage(anyKey);
			String errorMessage = "Error while downloading the assay: " + getPUG().getStatusMessage(message).getMessage();
			log.error(errorMessage);
			throw new Exception(errorMessage);
		}
	}

	protected String getAssayKey(int aid, String listKey) throws RemoteException {
		InputAssay assay = new InputAssay();
		assay.setAID(aid);
		assay.setColumns(AssayColumnsType.eAssayColumns_Complete);
		assay.setListKeySCIDs(listKey);
		String assayKey = getPUG().inputAssay(assay).getAssayKey();
		return assayKey;
	}

	public InputStream getAssayResults(int aid, EntrezHistoryKey key) throws Exception {
		String listKey = getListKey(key.getDatabase(), key.getWebEnv(), key.getQueryKey());
		return getAssayResults(aid, listKey);
	}

	public InputStream getAssayResults(int aid, String database, String searchTerm) throws Exception {
		EntrezHistoryKey key = EUtilsSoapFactory.getInstance().eSearch(database, searchTerm);
		String listKey = getListKey(key);
		return getAssayResults(aid, listKey);
	}

	public InputStream getAssayResults(int aid, String listKey) throws Exception {
		String assayKey = getAssayKey(aid, listKey);
		AnyKeyType anyKey = downloadAssay(assayKey, CompressType.eCompress_GZip);
		StatusType status = getOnCompleteStatus(anyKey);
		InputStream is = getAssayDownload(anyKey, status);
		return new GZIPInputStream(is);

	}

	public String getListKey(String database, String searchTerm) throws Exception {
		EntrezHistoryKey entrezKey = EUtilsSoapFactory.getInstance().eSearch(database, searchTerm);
		String key = getListKey(entrezKey);
		return key;
	}

	public String getListKey(EntrezHistoryKey key) throws RemoteException {
		return getListKey(key.getDatabase(), key.getWebEnv(), key.getQueryKey());
	}

	public String getListKey(String db, String webEnv, String queryKey) throws RemoteException {
		EntrezKey entrezKey = new EntrezKey();
		entrezKey.setDb(db);
		entrezKey.setWebenv(webEnv);
		entrezKey.setKey(queryKey);
		InputEntrez entrez = new InputEntrez();
		entrez.setEntrezKey(entrezKey);
		String listKey = getPUG().inputEntrez(entrez).getListKey();
		return listKey;
	}

	protected StatusType getOnCompleteStatus(AnyKeyType anyKey) throws Exception {
		return getOnCompleteStatus(anyKey, this.initSleepTime, this.sleepTime);
	}

	protected StatusType getOnCompleteStatus(AnyKeyType anyKey, long initSleepTime, long sleepTime) throws Exception {
		GetOperationStatus getStatus = new GetOperationStatus();
		getStatus.setGetOperationStatus(anyKey);
		StatusType status;
		int counter = 0;
		while ((status = getPUG().getOperationStatus(getStatus).getStatus()) == StatusType.eStatus_Running || status == StatusType.eStatus_Queued) {
			log.debug("Waiting for operation to finish...");
			if (counter < 2)
				Thread.sleep(initSleepTime);
			else
				Thread.sleep(sleepTime);
		}
		return status;
	}

	private PUGStub getPUG() {
		return pug;
	}

	private String inputStructure(String structure, FormatType type) throws RemoteException {
		InputStructure inputStructure = new InputStructure();
		inputStructure.setStructure(structure);
		inputStructure.setFormat(type);
		String structureKey = pug.inputStructure(inputStructure).getStrKey();
		return structureKey;
	}

	public int[] identitySearch(String molecule, FormatType formatType, int intervalMs, int tryLimit) throws RemoteException, InterruptedException {
		IdentitySearch iReq = new IdentitySearch();

		iReq.setStrKey(inputStructure(molecule, formatType));
		IdentitySearchOptions isOpt = new IdentitySearchOptions();
		isOpt.setEIdentity(IdentityType.eIdentity_SameStereoIsotope);
		iReq.setIdOptions(isOpt);
		String listKey = pug.identitySearch(iReq).getListKey();
		waitFor(listKey, intervalMs, tryLimit);
		GetIDList getIdlistReq = new GetIDList();
		getIdlistReq.setListKey(listKey);
		try {
			return pug.getIDList(getIdlistReq).getIDList().get_int();
		} catch (AxisFault ex) {
			String msg = ex.getMessage();
			if (msg.contains("Incomplete Entrez key - no hits"))
				return new int[0];
			throw ex;
		}
	}

	public void waitFor(String listKey, int intervalMs, int tryLimit) throws RemoteException, InterruptedException {
		GetOperationStatus statusRequest = new GetOperationStatus();
		AnyKeyType anyKey = new AnyKeyType();
		anyKey.setAnyKey(listKey);

		statusRequest.setGetOperationStatus(anyKey);
		StatusType status;
		long timeStart = System.currentTimeMillis();
		int count = 0;
		while ((status = getPUG().getOperationStatus(statusRequest).getStatus()) == StatusType.eStatus_Running || status == StatusType.eStatus_Queued) {
			if (count > tryLimit)
				break;
			Thread.sleep(intervalMs);
			long timeNow = System.currentTimeMillis();
			log.debug(String.format("Waiting on pug operation. Total duration = %ss", (timeNow - timeStart) / 1000));
			count = count + 1;
		}
		if (status != StatusType.eStatus_Success) {
			GetStatusMessage errorStatusRequest = new GetStatusMessage();
			errorStatusRequest.setGetStatusMessage(anyKey);
			log.error("Error: " + getPUG().getStatusMessage(errorStatusRequest).getMessage());
			throw new RuntimeException(getPUG().getStatusMessage(errorStatusRequest).getMessage());
		}
	}

	public EntrezKey getEntrezKey(String listKey) throws RemoteException {
		GetEntrezKey g = new GetEntrezKey();
		g.setListKey(listKey);
		GetEntrezKeyResponse resp = pug.getEntrezKey(g);
		return resp.getEntrezKey();
	}

	public URL getSDFile(PCIDType type, int[] ids) throws IOException, InterruptedException {
		ArrayOfInt arr = new ArrayOfInt();
		arr.set_int(ids);
		InputList list = new InputList();
		list.setIds(arr);
		list.setIdType(type);
		String listKey = getPUG().inputList(list).getListKey();

		Download download = new Download();
		download.setListKey(listKey);
		download.setEFormat(FormatType.eFormat_SDF);
		download.setECompress(CompressType.eCompress_GZip);
		String downloadKey = getPUG().download(download).getDownloadKey();

		GetOperationStatus statusRequest = new GetOperationStatus();
		AnyKeyType anyKey = new AnyKeyType();
		anyKey.setAnyKey(downloadKey);
		statusRequest.setGetOperationStatus(anyKey);
		StatusType status;
		long timeStart = System.currentTimeMillis();
		while ((status = getPUG().getOperationStatus(statusRequest).getStatus()) == StatusType.eStatus_Running || status == StatusType.eStatus_Queued) {
			Thread.sleep(10000);
			long timeNow = System.currentTimeMillis();
			System.out.println(String.format("Waiting on pug operation. Total duration = %ss", (timeNow - timeStart) / 1000));
		}

		if (status == StatusType.eStatus_Success) {
			GetDownloadUrl downloadUrl = new GetDownloadUrl();
			downloadUrl.setDownloadKey(downloadKey);
			URL url = new URL(getPUG().getDownloadUrl(downloadUrl).getUrl());
			return url;
		} else {
			GetStatusMessage errorStatusRequest = new GetStatusMessage();
			errorStatusRequest.setGetStatusMessage(anyKey);
			log.error("Error: " + getPUG().getStatusMessage(errorStatusRequest).getMessage());
			throw new RuntimeException(getPUG().getStatusMessage(errorStatusRequest).getMessage());
		}
	}

	public URL getSDFilefromCIDs(int[] cids) throws IOException, InterruptedException {
		return getSDFile(PCIDType.eID_CID, cids);
	}

	public URL getSDFilefromSIDs(int[] sids) throws IOException, InterruptedException {
		return getSDFile(PCIDType.eID_SID, sids);
	}
}