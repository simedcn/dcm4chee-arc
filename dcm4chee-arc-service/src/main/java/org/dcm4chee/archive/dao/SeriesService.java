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
 * Portions created by the Initial Developer are Copyright (C) 2011
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
package org.dcm4chee.archive.dao;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.dcm4che.data.Attributes;
import org.dcm4chee.archive.common.QueryParam;
import org.dcm4chee.archive.entity.PatientStudySeriesAttributes;
import org.dcm4chee.archive.entity.Series;
import org.dcm4chee.archive.entity.Study;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
@Stateless
public class SeriesService {

    @PersistenceContext
    private EntityManager em;

    @EJB
    private CountRelatedInstancesService countRelatedInstancesService;

    public Attributes getAttributes(Long seriesPk, QueryParam queryParam) {
        PatientStudySeriesAttributes result = (PatientStudySeriesAttributes)
                em.createNamedQuery(Series.PATIENT_STUDY_SERIES_ATTRIBUTES)
                  .setParameter(1, seriesPk)
                  .getSingleResult();
        boolean includeQueryAttributes = queryParam != null;
        if (includeQueryAttributes )
            updateQueryAttributes(seriesPk, queryParam, result);
        return result.getAttributes(includeQueryAttributes);
    }


    private void updateQueryAttributes(Long seriesPk, QueryParam queryParam,
            PatientStudySeriesAttributes result) {
        if (result.getNumberOfSeriesRelatedInstances() == -1)
            result.setNumberOfSeriesRelatedInstances(
                    calculateNumberOfSeriesRelatedInstances(seriesPk, queryParam));
        if (result.getNumberOfStudyRelatedInstances() == -1) {
            int[] a = calculateNumberOfStudyRelatedSeriesAndInstances(
                    result.getStudyPk(), queryParam);
            result.setNumberOfStudyRelatedSeries(a[0]);
            result.setNumberOfStudyRelatedInstances(a[1]);
        }
    }

    public int calculateNumberOfSeriesRelatedInstances(Long seriesPk, QueryParam queryParam) {
        if (em.createNamedQuery(Series.COUNT_REJECTED_INSTANCES, Long.class)
                  .setParameter(1, seriesPk).getSingleResult() > 0)
            return countRelatedInstancesService
                    .countSeriesRelatedInstances(seriesPk, queryParam);
        int num = em.createNamedQuery(Series.COUNT_RELATED_INSTANCES, Long.class)
                .setParameter(1, seriesPk).getSingleResult().intValue();
        em.createNamedQuery(Series.UPDATE_NUMBER_OF_SERIES_RELATED_INSTANCES)
            .setParameter(1, num)
            .setParameter(2, seriesPk)
            .executeUpdate();
        return num;
    }

    public int[] calculateNumberOfStudyRelatedSeriesAndInstances(Long studyPk,
            QueryParam queryParam) {
        if(em.createNamedQuery(Study.COUNT_REJECTED_INSTANCES, Long.class)
                  .setParameter(1, studyPk).getSingleResult() > 0)
            return countRelatedInstancesService
                    .countStudyRelatedSeriesAndInstances(studyPk, queryParam);
            
        int numSeries = em.createNamedQuery(Study.COUNT_RELATED_SERIES, Long.class)
                .setParameter(1, studyPk).getSingleResult().intValue();
        int numInstances = em.createNamedQuery(Study.COUNT_RELATED_INSTANCES, Long.class)
                .setParameter(1, studyPk).getSingleResult().intValue();
        em.createNamedQuery(Study.UPDATE_NUMBER_OF_STUDY_RELATED_SERIES_AND_INSTANCES)
            .setParameter(1, numSeries)
            .setParameter(2, numInstances)
            .setParameter(3, studyPk)
            .executeUpdate();
        return new int[] { numSeries, numInstances };
    }

}