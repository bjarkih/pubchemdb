package edu.scripps.fl.pubchem.depo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.dom4j.Document;
import org.dom4j.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.scripps.fl.dom4j.util.AppendingVisitorSupport;

public class PCWebSession extends PCWebSessionBase {

	private static final Logger log = LoggerFactory.getLogger(PCWebSession.class);
	
	public boolean DEBUGGING = false;
	private HttpClient client = new DefaultHttpClient();
	

	public HttpClient getHttpClient() {
		return client;
	}
	
	/**
	 * 
	 * Returns CSV data file for the AID.
	 * 
	 * @param aid
	 * @return
	 * @throws Exception
	 */
	public InputStream getAssayCSV(int aid) throws Exception {
		return getAssayFile("alldata", "csv", aid);
	}

	protected InputStream getAssayFile(String format, String ext, int aid) throws Exception {
		List<NameValuePair> params = addParameters(new ArrayList<NameValuePair>(), "aid", aid, "q", "r", "exptype", format, "zip", "gzip",
				"resultsummary", "detail", "releasehold", "t");
		URI uri = URIUtils.createURI("http", SITE, 80, "/assay/assay.cgi", URLEncodedUtils.format(params, "UTF-8"), null);
		Document document = new WaitOnRequestId(uri).asDocument();
		return getFtpLinkAsStream(document);
	}
	
	/**
	 * 
	 * Returns a description xml file for an aid
	 * 
	 * @param aid
	 * @return
	 * @throws Exception
	 */
	public InputStream getDescrXML(int aid) throws Exception {
		// if we don't provide the version, we get the latest one anyway.
		// http://pubchem.ncbi.nlm.nih.gov/assay/assay.cgi?aid=2551&version=1.1&q=expdesc_xmldisplay
		List<NameValuePair> params = addParameters(new ArrayList<NameValuePair>(), "aid", aid, "q", "expdesc_xmldisplay");
		URI uri = URIUtils.createURI("http", SITE, 80, "/assay/assay.cgi", URLEncodedUtils.format(params, "UTF-8"), null);
		HttpResponse response = getHttpClient().execute(new HttpGet(uri));
		return response.getEntity().getContent();
	}

	/**
	 * 
	 * Returns an SDF file of substances in an AID.<br>
	 * It only works if on-hold SIDs are from your own center. If you submit AIDs for other centers, you cannot download their SIDs!
	 * 
	 * @param aid
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public InputStream getAssaySDF(int aid) throws Exception {
		String page = getDataTablePage(aid);
		String url = getLinkFromJavaScript(page, "Substance Structure");
		Document document = getDocument(url);
		MultipartEntity entity = new MultipartEntity();
		Node formNode = document.selectSingleNode("//form[1]");
		addFormParts(formNode, entity, new HashSet(Arrays.asList("savejob")));
		document = new WaitOnRequestId(postPage("http://" + SITE + "/pc_fetch/pc_fetch.cgi", entity)).asDocument();
		return getFtpLinkAsStream(document);
	}

	
	/**
	 * 
	 * Returns a list of SIDs that are present in an AID.<br>
	 * This is a convenience method. It first calls getAssayCSV because I can't figure out the link from the DataTable page.
	 * 
	 * @param aid
	 * @return
	 * @throws Exception
	 */
	public List<Long> getAssaySIDs(int aid) throws Exception {
		// really simple, no dependency parsing of the CSV file because I can't get the link from the DataTable page to work :-(
		InputStream is = getAssayCSV(aid);
		BufferedReader br = new BufferedReader(new InputStreamReader(is));
		String line = br.readLine(); // ignore header line;
		List<Long> list = new ArrayList<Long>();
		while (null != (line = br.readLine())) {
			String items[] = line.split(",");
			list.add(Long.parseLong(items[1])); // SID in 2nd column
		}
		return list;
	}

	/**
	 * 
	 * Returns the full XML file for an AID.
	 * 
	 * @param aid
	 * @return
	 * @throws Exception
	 */
	public InputStream getAssayXML(int aid) throws Exception {
		return getAssayFile("xml", "xml", aid);
	}

	protected String getDataTablePage(int aid) throws Exception {
		List<NameValuePair> params = addParameters(new ArrayList<NameValuePair>(), "aid", aid, "q", "r", "pagefrom", "BioAssaySummary", "acount",
				"0", "releasehold", "t", "activity" + aid, "");
		URI uri = URIUtils.createURI("http", SITE, 80, "/assay/assay.cgi", URLEncodedUtils.format(params, "UTF-8"), null);
		return new WaitOnRequestId(uri).asPage();
	}

	

	protected InputStream getFtpLinkAsStream(Document document) throws Exception {
		Node linkNode = document.selectSingleNode("//a[@href=starts-with(.,'ftp://')]");
		if (null != linkNode) {
			String url = linkNode.valueOf("@href");
			log.info("Found ftp link: " + url);
			return new GZIPInputStream(new URL(url).openStream());
		}
		File file = File.createTempFile(getClass().getName(), ".html");
		document.write(new FileWriter(file));
		throw new Exception("Received a PubChem web page without an ftp link. Please check " + file);
	}

	protected String getLinkFromJavaScript(String page, String linkName) throws Exception {
		Pattern pattern = Pattern.compile("\\[\"" + linkName + "\"\\s*,\\s*\"([^\\\"]+)");
		Matcher matcher = pattern.matcher(page);
		if (!matcher.find()) {
			File file = File.createTempFile(getClass().getName(), ".html");
			FileUtils.writeStringToFile(file, page);
			String error = "Could not determine " + linkName + " link on PubChem page. Please check " + file;
			throw new Exception(error);
		}
		String url = matcher.group(1);
		log.info("Found javascript link: " + url);
		return url;
	}

	/**
	 * Fetches an SDF file for the given CID or SID.
	 * 
	 * @param type
	 *            "cid" or "sid"
	 * @param id
	 *            the cid or sid
	 * @return sdf file as stream
	 * @throws Exception
	 */
	public InputStream getSDF(String type, int id) throws Exception {
		String href = String.format("http://pubchem.ncbi.nlm.nih.gov/summary/summary.cgi?%s=%s&disopt=SaveSDF", type, id);
		HttpResponse response = getHttpClient().execute(new HttpGet(href));
		return response.getEntity().getContent();
	}

	/**
	 * 
	 * Returns counts of active, all, inactive, inconclusive and probe substances in the aid.
	 * 
	 * @param aid
	 * @return OutComeCount 	
	 * @throws Exception
	 */
	public OutcomeCount getSubstanceOutcomeCounts(int aid) throws Exception {
		Document doc = getDocument("http://" + SITE + "/assay/assay.cgi?aid=" + aid);
		Node node = doc.selectSingleNode("//b[. = 'Substances: ']").getParent();
		AppendingVisitorSupport visitor = new AppendingVisitorSupport();
		node.accept(visitor);
		String text = visitor.getText();
		Pattern sectionPattern = Pattern.compile("Substances:(.+)", Pattern.MULTILINE);
		Matcher matcher = sectionPattern.matcher(text);
		matcher.find();
		text = matcher.group(1);
		Pattern countPattern = Pattern.compile("([\\w]+):\\s+(\\d+)");
		matcher = countPattern.matcher(text);
		OutcomeCount oc = new OutcomeCount();
		while (matcher.find()) {
			String outcome = matcher.group(1);
			int count = Integer.parseInt(matcher.group(2));
			if ("All".equalsIgnoreCase(outcome))
				oc.all = count;
			else if ("Active".equalsIgnoreCase(outcome))
				oc.active = count;
			else if ("Inactive".equalsIgnoreCase(outcome))
				oc.inactive = count;
			else if ("Inconclusive".equalsIgnoreCase(outcome))
				oc.inconclusive = count;
			else if ("Probe".equalsIgnoreCase(outcome))
				oc.probe = count;
		}
		return oc;
	}
}
