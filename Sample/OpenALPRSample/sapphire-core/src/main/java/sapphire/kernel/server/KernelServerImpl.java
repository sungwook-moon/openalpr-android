package sapphire.kernel.server;

import sapphire.app.AppEntryPoint;
import sapphire.common.AppObjectStub;
import sapphire.kernel.client.KernelClient;
import sapphire.kernel.common.GlobalKernelReferences;
import sapphire.kernel.common.KernelOID;
import sapphire.kernel.common.KernelObjectMigratingException;
import sapphire.kernel.common.KernelObjectNotCreatedException;
import sapphire.kernel.common.KernelObjectNotFoundException;
import sapphire.kernel.common.KernelRPCException;

import sapphire.kernel.common.KernelRPC;
import sapphire.kernel.common.KernelUtility;
import sapphire.oms.OMSServer;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;


/** 
 * Sapphire Kernel Server. Runs on every Sapphire node, knows how to talk to the OMS, handles RPCs and has a client for making RPCs.
 * 
 * @author iyzhang
 *
 */

public class KernelServerImpl implements KernelServer{
	private static Logger logger = Logger.getLogger("sapphire.kernel.server.KernelServerImpl");
	private InetSocketAddress host;
	/** manager for kernel objects that live on this server */
	private KernelObjectManager objectManager;
	/** stub for the OMS */
	public static OMSServer oms;
	/** local kernel client for making RPCs */
	private KernelClient client;

	/** region information where this kernel server is running */
	private static String region = null;
	
	public KernelServerImpl(InetSocketAddress host, InetSocketAddress omsHost) {
		logger.setLevel(Level.ALL);
		ConsoleHandler handler = new ConsoleHandler();
		handler.setLevel(Level.ALL);
		handler.setFormatter(new SimpleFormatter());
		logger.addHandler(handler);

		objectManager = new KernelObjectManager();
	    Registry registry;
		try {
			logger.info("OMS : " + omsHost.getAddress() + ":" + omsHost.getPort());
			registry = LocateRegistry.getRegistry(KernelUtility.getHostName(omsHost), omsHost.getPort());
			oms = (OMSServer) registry.lookup("SapphireOMS");
		} catch (Exception e) {
			logger.severe("Could not find OMS: " + e.toString());
		}

		this.host = host;
		client = new KernelClient(oms);
		GlobalKernelReferences.nodeServer = this;
	}
	
	/** RPC INTERFACES **/
	
	/**
	 * Invoke an RPC on this kernel server. This is a public RMI interface.
	 * 
	 * @param rpc All of the information about the RPC, the object id, the method and arguments
	 * @return the return value from the method invocation
	 */
	@Override
	public Object makeKernelRPC(KernelRPC rpc) throws RemoteException, KernelObjectNotFoundException, KernelObjectMigratingException, KernelRPCException {
		KernelObject object = null;
		object = objectManager.lookupObject(rpc.getOID());
		
		logger.info("Invoking RPC on Kernel Object with OID: " + rpc.getOID() + "with rpc:" + rpc.getMethod() + " params: " + rpc.getParams().toString());
		Object ret = null;
		try {
			ret = object.invoke(rpc.getMethod(), rpc.getParams());
		} catch (Exception e) {
			throw new KernelRPCException(e);
		}
		return ret;
	}
	
	/**
	 * Move a kernel object to this server.
	 * 
	 * @param oid the kernel object id
	 * @param object the kernel object to be stored on this server
	 */
	public void copyKernelObject(KernelOID oid, KernelObject object) throws RemoteException, KernelObjectNotFoundException {
		objectManager.addObject(oid, object);
		object.uncoalesce();
	}
	
	/** LOCAL INTERFACES **/
	/** 
	 * Create a new kernel object locally on this server.
	 * 
	 * @param stub
	 */
	public KernelOID newKernelObject(Class<?> cl, Object ... args) throws KernelObjectNotCreatedException {
		KernelOID oid = null;
		// get OID
		try {
			oid = oms.registerKernelObject(host);
		} catch (RemoteException e) {
			throw new KernelObjectNotCreatedException("Error making RPC to OMS: "+e);
		}
		
		// Create the actual kernel object stored in the object manager
		objectManager.newObject(oid, cl, args);
		logger.fine("Created new Kernel Object on host: " + host + " with OID: " + oid.getID());
		
		return oid;
	}

	/**
	 * Move object from this server to host.
	 * @param host
	 * @param oid
	 * @throws RemoteException
	 * @throws KernelObjectNotFoundException
	 */
	public void moveKernelObjectToServer(InetSocketAddress host, KernelOID oid) throws RemoteException, KernelObjectNotFoundException {
		if (host.equals(this.host)) {
			return;
		}
		
		KernelObject object = objectManager.lookupObject(oid);
		object.coalesce();
		
		logger.fine("Moving object " + oid.toString() + " to " + host.toString());
		
		try {
			client.copyObjectToServer(host, oid, object);
		} catch (RemoteException e) {
			e.printStackTrace();
			throw new RemoteException("Could not contact destination server.");
		}
		
		try {
			oms.registerKernelObject(oid, host);
		} catch (RemoteException e) {
			throw new RemoteException("Could not contact oms to update kernel object host.");
		}
		
		objectManager.removeObject(oid);
	}
	
	public Serializable getObject(KernelOID oid) throws KernelObjectNotFoundException {
		KernelObject object = objectManager.lookupObject(oid);
		return object.getObject();
	}
	
	/**
	 * Get the local hostname
	 * @return IP address of host that this server is running on
	 */
	public InetSocketAddress getLocalHost() {
		return host;
	}
	
	/**
	 * Get the kernel client for making RPCs
	 * @return the kernel client in this server
	 */
	public KernelClient getKernelClient() {
		return client;
	}
		
	/**
	 * Start the first server-side app object
	 */
	@Override
	public AppObjectStub startApp(String className) throws RemoteException {
		AppObjectStub appEntryPoint = null;
		try {
			logger.info("Starting APP: " + className);
			AppEntryPoint entryPoint =  (AppEntryPoint) Class.forName(className).newInstance();

			logger.info("Instance was created. Starting the entryPoint.");
            appEntryPoint = entryPoint.start();
		} catch (Exception e) {
			logger.severe("Could not start app: " + e.toString());
			e.printStackTrace();
		}
		return appEntryPoint;
	}
//
//	/**
//	 * Start the first server-side app object
//	 */
//	@Override
//	public AppObjectStub startApp(String className, Object object) throws RemoteException {
//		AppObjectStub appEntryPoint = null;
//		try {
//			AppEntryPoint entryPoint =  (AppEntryPoint) Class.forName(className).newInstance();
//            appEntryPoint = entryPoint.start();
//		} catch (Exception e) {
//			logger.severe("Could not start app");
//			e.printStackTrace();
//		}
//		return appEntryPoint;
//	}

	public class MemoryStatThread extends Thread {
		public void run() {
			while (true) {
				try {
					Thread.sleep(300000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				System.out.println("Total memory: " + Runtime.getRuntime().totalMemory() + " Bytes");
				System.out.println("Free memory: " + Runtime.getRuntime().freeMemory() + " Bytes");
			}
		}
	}
	
	public MemoryStatThread getMemoryStatThread() {
		return new MemoryStatThread();
	}
	
	
	/**
	 * At startup, contact the OMS.
	 * @param args
	 */
	public static void main(String args[]) {
		boolean skipOmsRegistration = false;

		logger.setLevel(Level.ALL);
		ConsoleHandler handler = new ConsoleHandler();
		handler.setLevel(Level.ALL);
		handler.setFormatter(new SimpleFormatter());
		logger.addHandler(handler);

		if (args.length >= 5) {
			// There is a region (e.g., processing entity signature such as device or server).
			region = args[4];
			logger.info("Region information found. Kernel server region: " + region);
			if (args.length == 6 && args[5].equalsIgnoreCase("skipOMS")) {
				skipOmsRegistration = true;
			}

		} else if (args.length != 4) {
			System.out.println("Incorrect arguments to the kernel server");
			System.out.println("[host ip] [host port] [oms ip] [oms port]");
			return;
		}
		
		InetSocketAddress host, omsHost;
		
		try {
			host = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
			omsHost = new InetSocketAddress(args[2], Integer.parseInt(args[3]));
		} catch (NumberFormatException e) {
			System.out.println("Incorrect arguments to the kernel server");
			System.out.println("[host ip] [host port] [oms ip] [oms port]");
			return;
		}

		logger.info("java.rmi.server.hostname" + KernelUtility.getHostName(host));
		System.setProperty("java.rmi.server.hostname", KernelUtility.getHostName(host));

		try {
			KernelServerImpl server = new KernelServerImpl(host, omsHost);
			KernelServer stub = (KernelServer) UnicastRemoteObject.exportObject(server, 0);
			Registry registry = LocateRegistry.createRegistry(Integer.parseInt(args[1]));
			registry.rebind("SapphireKernelServer", stub);

			if (!skipOmsRegistration) {
				// Skips OMS registration when OMS already has server information and Kernel cannot reach OMS (e.g., OMS is running on internal networks).
				oms.registerKernelServer(host, region);
			}
			
			logger.info("Server ready! Host: " + host.getHostString() + " region: " + region);

			/* Start a thread that print memory stats */
			server.getMemoryStatThread().start();

		} catch (Exception e) {
			logger.severe("Cannot start Sapphire Kernel Server");
			e.printStackTrace();
		}
	}
}
