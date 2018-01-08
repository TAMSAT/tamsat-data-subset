/*******************************************************************************
 * Copyright (c) 2018 The University of Reading
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the University of Reading, nor the names of the
 *    authors or contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package uk.org.tamsat.dataserver;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;

import uk.ac.rdg.resc.edal.exceptions.EdalException;

/**
 * Class that contains the parameters of the user's request. Parameter names are
 * not case sensitive.
 * 
 * @author Jon Blower
 * @author Guy Griffiths
 */
public class TamsatRequestParams {
    private Map<String, String> paramMap = new HashMap<String, String>();

    /**
     * Creates a new TamsatRequestParams object from the given Map of parameter
     * names and values (normally gained from
     * HttpServletRequest.getParameterMap()). The Map matches parameter names
     * (Strings) to parameter values (String arrays).
     * 
     * @param httpRequestParamMap
     *            The {@link Map} to generate request values from. This is
     *            unparameterised, since it most often comes from
     *            {@link HttpServletRequest#getParameterMap()}, which is
     *            unparameterised
     */
    public TamsatRequestParams(Map<?, ?> httpRequestParamMap) {
        @SuppressWarnings("unchecked")
        Map<String, String[]> httpParamMap = (Map<String, String[]>) httpRequestParamMap;

        for (String name : httpParamMap.keySet()) {
            String[] values = httpParamMap.get(name);
            assert values.length >= 1;
            String key = name.trim().toLowerCase();
            String value = values[0].trim();
            if (key.equals("time")) {
                value = value.replace(' ', '+');
            }
            this.paramMap.put(key, value);
        }
    }

    private TamsatRequestParams() {
    }

    /**
     * Create a new {@link TamsatRequestParams} from this one, merging in new
     * values
     * 
     * @param mergeParameters
     *            The new values to merge in
     * @return A new instance of {@link TamsatRequestParams} containing these
     *         parameters plus the required new ones. In the case of a name
     *         conflict existing parameters will be overwritten by the merged
     *         ones.
     */
    public TamsatRequestParams mergeParameters(Map<String, String> mergeParameters) {
        TamsatRequestParams ret = new TamsatRequestParams();
        ret.paramMap = paramMap;

        for (String name : mergeParameters.keySet()) {
            String value = mergeParameters.get(name);
            String key = name.trim().toLowerCase();
            value = value.trim();
            ret.paramMap.put(key, value);
        }
        return ret;
    }

    /**
     * Returns the value of the parameter with the given name as a String, or
     * <code>null</code> if the parameter does not have a value. This method is
     * not sensitive to the case of the parameter name. Use getWmsVersion() to
     * get the requested WMS version.
     */
    public String getString(String paramName) {
        return paramMap.get(paramName.toLowerCase());
    }

    /**
     * Returns the value of the parameter with the given name, throwing an
     * {@link EdalException} if the parameter does not exist. Use
     * getMandatoryWmsVersion() to get the requested WMS version.
     */
    public String getMandatoryString(String paramName) throws EdalException {
        String value = this.getString(paramName);
        if (value == null) {
            throw new EdalException(
                    "Must provide a value for parameter " + paramName.toUpperCase());
        }
        return value;
    }

    /**
     * Returns the value of the parameter with the given name as a integer, or
     * the provided default if no parameter with the given name has been
     * supplied. Throws a WmsException if the parameter does not exist or if the
     * value is not a valid integer.
     */
    public int getInt(String paramName, int defaultValue) throws EdalException {
        String value = this.getString(paramName);
        if (value == null)
            return defaultValue;
        return parseInt(paramName, value);
    }

    /**
     * Returns the value of the parameter with the given name as an integer,
     * throwing a WmsException if the parameter does not exist or if the value
     * is not a valid integer.
     */
    public int getMandatoryInt(String paramName) throws EdalException {
        String value = this.getString(paramName);
        if (value == null) {
            throw new EdalException(
                    "Must provide a value for parameter " + paramName.toUpperCase());
        }
        return parseInt(paramName, value);
    }

    private static int parseInt(String paramName, String value) throws EdalException {
        try {
            int i = Integer.parseInt(value);
            return i;
        } catch (NumberFormatException nfe) {
            throw new EdalException(
                    "Parameter " + paramName.toUpperCase() + " must be a valid positive integer");
        }
    }

    /**
     * Returns the value of the parameter with the given name, or the supplied
     * default value if the parameter does not exist.
     */
    public String getString(String paramName, String defaultValue) {
        String value = this.getString(paramName);
        if (value == null)
            return defaultValue;
        return value;
    }

    /**
     * Returns the value of the parameter with the given name as a boolean
     * value, or the provided default if no parameter with the given name has
     * been supplied.
     * 
     * @throws EdalException
     *             if the value is not a valid boolean string ("true" or
     *             "false", case-insensitive).
     */
    public Boolean getBoolean(String paramName, Boolean defaultValue) throws EdalException {
        String value = this.getString(paramName);
        if (value == null)
            return defaultValue;
        value = value.trim();
        if ("true".equalsIgnoreCase(value))
            return true;
        if ("false".equalsIgnoreCase(value))
            return false;
        throw new EdalException("Invalid boolean value for parameter " + paramName);
    }

    /**
     * Returns the value of the parameter with the given name, or the supplied
     * default value if the parameter does not exist.
     * 
     * @throws EdalException
     *             if the value of the parameter is not a valid double-precision
     *             number
     */
    public double getDouble(String paramName, double defaultValue) throws EdalException {
        String value = this.getString(paramName);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException nfe) {
            throw new EdalException("Parameter " + paramName.toUpperCase()
                    + " must be a valid floating-point number");
        }
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder("Request Parameters:\n");
        for (Entry<String, String> param : paramMap.entrySet()) {
            ret.append("\t" + param.getKey() + ": " + param.getValue() + "\n");
        }
        return ret.toString();
    }

}
