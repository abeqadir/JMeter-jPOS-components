package org.apache.jmeter.samplers.jpos;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.jmeter.gui.custom.JPOSConfigGui;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.ChannelHelper;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.jpos.iso.BaseChannel;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOPackager;
import org.jpos.iso.packager.GenericPackager;
import org.jpos.util.FieldUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * @author "Yoann Ciabaud" <yoann.ciabaud@monext.fr>
 * @author "Erlangga" <erlangga258@gmail.com>
 */
public class JPOSSampler extends AbstractSampler {

	// https://svn.apache.org/repos/asf/jmeter/tags/v2_8/src/protocol/tcp/org/apache/jmeter/protocol/tcp/sampler/TCPSampler.java

	private static final Logger LOGGER = LoggingManager.getLoggerForClass();
	private static final Integer MAX_ISOBIT = Integer.valueOf(128);
	protected ISOPackager customPackager;
	private BaseChannel baseChannel;
	protected HashMap<String, String> isoMap;
	private boolean initialized = false;

	public final static String SERVER = "JPOSSampler.server"; //$NON-NLS-1$
	public final static String PORT = "JPOSSampler.port"; //$NON-NLS-1$
	public final static String TIMEOUT = "JPOSSampler.timeout"; //$NON-NLS-1$
	public final static String CHANNEL = "JPOSSampler.channel";
	public final static String PACKAGER = "JPOSSampler.packager";
	public final static String REQUEST = "JPOSSampler.request";
	public final static String RETURN_TYPE_KEY = "JPOSSampler.returnType";

	public JPOSSampler() {
		LOGGER.info("call constructor() ...");
	}

	public void initialize() throws Exception {
		LOGGER.info("call initilalize() ...");
		processPackagerFile();
		processDataRequest();
		if (customPackager != null) {
			LOGGER.info("customPackager available ...");
			String server = obtainServer();
			int port = obtainPort();
			String channel = obtainChannel();
			final String threadName = getCurrentThreadName();
			LOGGER.info("current thread is " + threadName + "[" + channel + ":" + server + ":" + port+ "]");

			ChannelHelper channelHelper = new ChannelHelper();
			String header = isoMap.get("header");
			if (header != null) {
				channelHelper.setTpdu(header);
			} else {
				channelHelper.setTpdu("0000000000"); // default
			}

			baseChannel = channelHelper.getChannel(server, port, customPackager, channel);
			LOGGER.info("initialize channel " + baseChannel.getHost() + " port " + baseChannel.getPort());
			initialized = true;
		}
	}

	protected String obtainChannel() {
		return getPropertyAsString(CHANNEL);
	}

	protected int obtainPort() {
		return getPropertyAsInt(PORT);
	}

	protected String obtainServer() {
		return getPropertyAsString(SERVER);
	}

	public void setServer(String server){
		setProperty(SERVER,server);
	}

	public void setPort(String port){
		setProperty(PORT,port);
	}

	public String getCurrentThreadName() {
		return JMeterContextService.getContext().getThread().getThreadName();
	}

    private ISOMsg buildISOMsg() throws ISOException {
        LOGGER.info("building iso message");
        ISOMsg isoReq = new ISOMsg();
        isoReq.setMTI(isoMap.get("mti"));

        int i = 1;
        String field = null;
        while (i < MAX_ISOBIT.intValue()) {
            if ((field = isoMap.get("bit." + i)) != null) {
                if (field.equalsIgnoreCase("auto")) {
                    isoReq.set(i, FieldUtil.getValue(i));
                    // else if (field.equalsIgnoreCase("stan"))
                    // this.isoReq.set(i,
                    // ISOUtil.zeropad(this.isoReq.getString(11), 8));
                    // else if (field.equalsIgnoreCase("tlv"))
                    // this.isoReq.set(i, tlvs.pack());
                    // else if (field.equalsIgnoreCase("counter"))
                    // this.isoReq.set(i, FieldUtil.getCounterValue());
                    // else if (field.startsWith("+"))
                    // this.isoReq.set(i, ISOUtil.hex2byte(field.substring(1)));
                    // else if (field.equalsIgnoreCase("nested")) {
                    // for (int n = 1; n < MAX_NESTED_ISOBIT.intValue(); n++) {
                    // if ((field = (String) reqProp.get("bit." + i + "." + n))
                    // != null)
                    // isoReq.set(i + "." + n, field);
                    // }
                } else {
                    isoReq.set(i, field);
                }
            }
            i++;
        }

        // String stan_tid_req = isoReq.getString(11) + isoReq.getString(41);
        LOGGER.info(LOGGERISOMsg(isoReq));
        return isoReq;
    }

    @Override
    public SampleResult sample(Entry e) {
        LOGGER.info("call sample() ...");
        SampleResult res = new SampleResult();
        res.setSampleLabel(getName());

		if (!initialized) {
            try {
				initialize();
			} catch (Exception e1) {
				res.setResponseMessage(e1.getMessage());
				res.setSuccessful(false);
				return res;
			}
		}

		String timeout = getPropertyAsString(TIMEOUT);
		int intTimeOut = 30 * 1000; // default
		if (timeout != null && !timeout.equals("")) {
			LOGGER.info("timeout = " + timeout);
			intTimeOut = Integer.parseInt(timeout);
		}
		res.sampleStart();

		try{
			res.setSuccessful(false);
			res.setResponseMessage("time-out");
			res.setResponseCode("ER");
			try {
				ISOMsg isoReq = buildISOMsg();
				if(isoReq != null) {
					res.setRequestHeaders(LOGGERISOMsg(isoReq));
				}
				ISOMsg isoRes = execute(intTimeOut, isoReq);
				if (isoRes != null) {
					String logISOMsg = null;
					if(getPropertyAsString(RETURN_TYPE_KEY).equalsIgnoreCase(JPOSConfigGui.JSON)){
						logISOMsg = LOGGERISOMsgToJSON(isoRes);
					}else {
						logISOMsg = LOGGERISOMsg(isoRes);
					}
					res.setResponseMessage(logISOMsg);
					res.setResponseCodeOK();
					res.setResponseData(logISOMsg, StandardCharsets.UTF_8.name());
					res.setSuccessful(true);
				}
			} catch (ISOException e1) {
				LOGGER.error(e1.getMessage());
				res.setResponseMessage(e1.getMessage());
			} catch (IOException e1) {
				LOGGER.error(e1.getMessage());
				res.setResponseMessage(e1.getMessage());
			}
		}finally {
			res.sampleEnd();
		}
		return res;
	}

	protected ISOMsg execute(int intTimeOut, ISOMsg isoReq) throws IOException, ISOException {
		LOGGER.info("connect to " + baseChannel.getHost() + " port " + baseChannel.getPort() + " time-out " + intTimeOut);
		baseChannel.connect();
		baseChannel.setTimeout(intTimeOut);
		baseChannel.send(isoReq);
		return baseChannel.receive();
	}

	private String LOGGERISOMsg(ISOMsg msg) {
		StringBuffer sBuffer = new StringBuffer();
		try {
			sBuffer.append("  MTI : " + msg.getMTI() + ", ");
			for (int i = 1; i <= msg.getMaxField(); i++) {
				if (msg.hasField(i)) {
					sBuffer.append("Field-" + i + " : " + msg.getString(i)
							+ ", Length : " + msg.getString(i).length() + "\n");
				}
			}
		} catch (ISOException e) {
			e.printStackTrace();
			sBuffer.append(e.getMessage());
		}
		return sBuffer.toString();
	}

	private String LOGGERISOMsgToJSON(ISOMsg msg){
		JsonObject restData = new JsonObject();
		try {
			restData.addProperty("MTI",msg.getMTI());
		} catch (ISOException e) {
			e.printStackTrace();
		}
		for (int i = 1; i <= msg.getMaxField(); i++) {
			if (msg.hasField(i)) {
				JsonObject fieldData = new JsonObject();
				fieldData.addProperty("Value",msg.getString(i));
				fieldData.addProperty("Length",msg.getString(i).length());
				restData.add("Field-"+i,fieldData);
			}
		}
		return new Gson().toJson(restData);
	}

	public static byte[] hexStringToByteArray(String s) {
		int len = s.length();
		byte[] data;

		if (len == 1) {
			data = new byte[1];
			data[0] = (byte) Character.digit(s.charAt(0), 16);
			return data;
		} else {
			data = new byte[len / 2];
			for (int i = 0; i < len; i += 2) {
				data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character
						.digit(s.charAt(i + 1), 16));
			}
		}
		return data;
	}

	protected void processDataRequest() throws IOException {
		String reqFile = obtainDataRequest();
		if (reqFile == null) {
			return;
		}
		mappingISOProp(reqFile);
	}

	private void mappingISOProp(String data) {
		HashMap<String, String> map = new HashMap<String, String>();
		BufferedReader bufReader = new BufferedReader(new StringReader(data));
		String line = null;
		try {
			while ((line = bufReader.readLine()) != null) {
				String parts[] = line.split("=");
				map.put(parts[0], parts[1]);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		isoMap = map;
	}

	protected String obtainDataRequest() {
		return getPropertyAsString(REQUEST);
	}

	public void processPackagerFile() {
		String packagerFile = getPropertyAsString(PACKAGER);
		if (packagerFile != null) {
			File initialFile = new File(packagerFile);
			LOGGER.info("initFile = " + initialFile.getAbsolutePath());
			if (initialFile.exists()) {
				LOGGER.info("file exists");
				try {
					InputStream targetStream = new FileInputStream(initialFile);
					if (targetStream != null) {
						customPackager = new GenericPackager(targetStream);
					}				
				} catch (FileNotFoundException e) {
					LOGGER.warn(e.getMessage());
					e.printStackTrace();
				} catch (ISOException e) {
					LOGGER.warn(e.getMessage());
					e.printStackTrace();
				}
			} else {
				LOGGER.info("file not exists");
			}
		}
	}
}
