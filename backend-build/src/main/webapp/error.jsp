<%@ page isErrorPage="true" import="java.io.*" contentType="text/html"%>
<!DOCTYPE html>
<html lang="en">

<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="viewport" content="width=device-width, initial-scale=1">
<!-- The above 3 meta tags *must* come first in the head; any other head content must come *after* these tags -->
<meta name="author" content="Guy Griffiths">
<meta name="description" content="">

<link rel="stylesheet" href="css/tamsat.css">
<title>TAMSAT Data Subset</title>
</head>

<body>
	<img src="img/header.png" />
	<h1>A Server Error Has Occurred</h1>
	A problem has occurred when trying to process this request. The message from the server is:
	<p class="error"><%=exception.getMessage()%></p>
	<p>Please contact <a href="tamsat@reading.ac.uk">the TAMSAT project</a> if this error continues to occur.</p>
</body>