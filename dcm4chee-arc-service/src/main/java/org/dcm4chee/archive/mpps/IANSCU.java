/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2012
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.archive.mpps;

import java.io.IOException;

import javax.annotation.Resource;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;

import org.dcm4che.data.Attributes;
import org.dcm4che.data.UID;
import org.dcm4che.net.ApplicationEntity;
import org.dcm4che.net.Association;
import org.dcm4che.net.Device;
import org.dcm4che.net.DimseRSP;
import org.dcm4che.net.TransferCapability;
import org.dcm4che.net.pdu.AAssociateRQ;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4che.util.UIDUtils;
import org.dcm4chee.archive.Archive;
import org.dcm4chee.archive.conf.ArchiveAEExtension;
import org.dcm4chee.archive.jms.JMSService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 */
public class IANSCU implements MessageListener {

    public static final Logger LOG = LoggerFactory.getLogger(IANSCU.class);

    @Resource(mappedName="java:/queue/ianscu")
    private Queue ianSCUQueue;

    public void start(Device device) throws JMSException {
        Archive.getInstance().addMessageListener(ianSCUQueue, this);
    }

    public void stop() {
        try {
            Archive.getInstance().removeMessageListener(this);
        } catch (JMSException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void scheduleIAN(final String localAET, final String remoteAET,
            final Attributes ian, final int retries, int delay) {
        try {
            Archive.getInstance()
                .sendMessage(ianSCUQueue, new JMSService.MessageCreator() {

                @Override
                public Message createMessage(Session session) throws JMSException {
                    ObjectMessage msg = session.createObjectMessage(ian);
                    msg.setStringProperty("LocalAET", localAET);
                    msg.setStringProperty("RemoteAET", remoteAET);
                    msg.setIntProperty("Retries", retries);
                    return msg;
                }},
             delay);
        } catch (JMSException e) {
            LOG.error("Failed to schedule IAN to " + remoteAET, e);
       }
    }

    @Override
    public void onMessage(Message msg) {
        try {
            process((ObjectMessage) msg);
        } catch (Throwable th) {
            LOG.warn("Failed to process " + msg, th);
        }
    }

    private void process(ObjectMessage msg) throws JMSException {
        String remoteAET = msg.getStringProperty("RemoteAET");
        String localAET = msg.getStringProperty("LocalAET");
        int retries = msg.getIntProperty("Retries");
        Attributes ian = (Attributes) msg.getObject();
        ApplicationEntity localAE = Archive.getInstance().getDevice()
                .getApplicationEntity(localAET);
        if (localAE == null) {
            LOG.warn("Failed to send IAN to {} - no such local AE: {}",
                    remoteAET, localAET);
            return;
        }
        TransferCapability tc = localAE.getTransferCapabilityFor(
                UID.InstanceAvailabilityNotificationSOPClass, TransferCapability.Role.SCU);
        if (tc == null) {
            LOG.warn("Failed to send IAN to {} - local AE: {} does not support Instance Availability Notification SOP Class in SCU Role",
                    remoteAET, localAET);
            return;
        }
        AAssociateRQ aarq = new AAssociateRQ();
        aarq.addPresentationContext(
                        new PresentationContext(
                                1,
                                UID.InstanceAvailabilityNotificationSOPClass,
                                tc.getTransferSyntaxes()));
        try {
            ApplicationEntity remoteAE = Archive.getInstance()
                    .findApplicationEntity(remoteAET);
            Association as = localAE.connect(remoteAE, aarq);
            DimseRSP rsp = as.ncreate(UID.InstanceAvailabilityNotificationSOPClass, 
                    UIDUtils.createUID(), ian, null);
            rsp.next();
            try {
                as.release();
            } catch (IOException e) {
                LOG.info("{}: Failed to release association to {}", as, remoteAET);
            }
        } catch (Exception e) {
            ArchiveAEExtension aeExt = localAE.getAEExtension(ArchiveAEExtension.class);
            if (aeExt != null && retries < aeExt.getIANMaxRetries()) {
                int delay = aeExt.getIANRetryInterval();
                LOG.info("Failed to send IAN to "
                            + remoteAET + " - retry in "  + delay + "s", e);
                scheduleIAN(localAET, remoteAET, ian, retries + 1, delay);
            } else {
                LOG.warn("Failed to send IAN to " + remoteAET, e);
            }
        }
    }

}
