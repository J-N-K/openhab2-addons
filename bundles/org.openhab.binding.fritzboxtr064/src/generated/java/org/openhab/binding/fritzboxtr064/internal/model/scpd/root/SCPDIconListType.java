
package org.openhab.binding.fritzboxtr064.internal.model.scpd.root;

import java.io.Serializable;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for iconListType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="iconListType"&gt;
 *   &lt;complexContent&gt;
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType"&gt;
 *       &lt;sequence&gt;
 *         &lt;element name="icon" type="{urn:dslforum-org:device-1-0}iconType"/&gt;
 *       &lt;/sequence&gt;
 *     &lt;/restriction&gt;
 *   &lt;/complexContent&gt;
 * &lt;/complexType&gt;
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "iconListType", propOrder = {
    "icon"
})
public class SCPDIconListType implements Serializable
{

    private final static long serialVersionUID = 1L;
    @XmlElement(required = true)
    protected SCPDIconType icon;

    /**
     * Gets the value of the icon property.
     * 
     * @return
     *     possible object is
     *     {@link SCPDIconType }
     *     
     */
    public SCPDIconType getIcon() {
        return icon;
    }

    /**
     * Sets the value of the icon property.
     * 
     * @param value
     *     allowed object is
     *     {@link SCPDIconType }
     *     
     */
    public void setIcon(SCPDIconType value) {
        this.icon = value;
    }

}
