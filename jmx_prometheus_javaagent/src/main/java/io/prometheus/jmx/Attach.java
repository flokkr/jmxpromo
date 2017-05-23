package io.prometheus.jmx;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.util.List;

public class Attach {
  public static void main(String[] args) throws Exception {

    if (args.length < 2 || args.length > 3) {
      System.err.println("Usage: java -cp dir/agent.jar:/.../jvm/java-8-jdk/lib/tools.jar " +
          "io.prometheus.jmx.shaded.io.prometheus.jmx.Attach pid [host:]<port>:<yaml configuration file>");
      System.exit(1);
    }


    List<VirtualMachineDescriptor> vms = VirtualMachine.list();
    VirtualMachineDescriptor selectedVM = null;
    for (VirtualMachineDescriptor vmd : vms) {
      if (vmd.id().equals(args[0])) {
        selectedVM = vmd;
      }
    }

    if (selectedVM == null) {
      System.err.println("No such java process with pid=" + args[0]);
      System.exit(-1);
    }

    VirtualMachine attachedVm = VirtualMachine.attach(selectedVM);
    File currentJarFile = new File(Attach.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

    attachedVm.loadAgent(currentJarFile.getAbsolutePath(), args[1]);
    attachedVm.detach();

  }
}
