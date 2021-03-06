package org.motechproject.nms.ldapbrowser.support.web;

import org.motechproject.nms.ldapbrowser.ldap.DistrictInfo;
import org.motechproject.nms.ldapbrowser.ldap.apacheds.ApacheDsUser;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlSeeAlso;
import java.util.List;

@XmlRootElement
@XmlSeeAlso({ApacheDsUser.class, DistrictInfo.class})
public class DtData<T> {

    private List<T> data;
    private long recordsTotal;
    private int recordsFiltered;

    public DtData() {
    }

    public DtData(List<T> data, long recordsTotal) {
        this.data = data;
        this.recordsTotal = recordsTotal;
        this.recordsFiltered = data.size();
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data;
    }

    public long getRecordsTotal() {
        return recordsTotal;
    }

    public void setRecordsTotal(long recordsTotal) {
        this.recordsTotal = recordsTotal;
    }

    public int getRecordsFiltered() {
        return recordsFiltered;
    }

    public void setRecordsFiltered(int recordsFiltered) {
        this.recordsFiltered = recordsFiltered;
    }
}
